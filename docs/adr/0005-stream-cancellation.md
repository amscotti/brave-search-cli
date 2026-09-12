# ADR 0005: Stream cancellation and exit-code mechanism

- Status: Accepted
- Date: 2026-08-30

## Context

Before the transport and command architecture harden, the process-level lifecycle of a
streaming run had to be proven with executed evidence on both executables the build produces:
the installed JVM launcher and the GraalVM native image. The risks were process-level and
therefore invisible to in-process tests: how an interrupt becomes exit code 130, how a closed
stdout pipe becomes a silent exit 0 instead of a SIGPIPE death (141), how deadlines behave when
a reader is parked inside a silent read, and who owns which executor. The hidden
`brave-search stream-probe` command, a scripted loopback server, and a process harness were
built to answer these questions against real child processes and real signals.

## Decision

### One first-terminal-cause latch per run

Every run owns one `application.stream.CancellationContext`. SIGINT, a broken output pipe, the
idle deadline, the wall deadline, a subscriber failure, a transport failure, and an explicit
close all race to latch exactly one terminal cause there, and the first latch wins
irrevocably. The run's exit code is derived from that cause: SIGINT maps to 130, BROKEN_PIPE
and a clean CLOSED to 0, and the remaining causes (idle, wall, subscriber, transport) to 6. A
CLOSED observed at the end of a normally driven run means "body exhausted", which is success;
the failure causes are distinguishable because they latch before any close path runs.

Every terminal path latches. The publisher latches its own causes — both deadlines, subscriber
failure (including a negative-request protocol violation, latched before the error is
delivered), and the closing path — and the relay latches TRANSPORT_FAILURE for any error that
reaches it without a cause having won first, which is the shape of a raw mid-body connection
reset or truncation. This invariant is what makes the command's unconditional await safe: a
run can never end in a delivered `onError` with no cause latched, so awaiting the latch can
never hang.

### Registry-owned contexts and the signal latches

`bootstrap.ExchangeRegistry` is a plain non-static object holding the contexts of live
exchanges; it implements the `application.stream.CancellationRegistry` contract so commands
never reference a bootstrap type. The probe command registers its context before the exchange
opens — so an interrupt during the connect window is still latched — and detaches it on close
through the returned handle.

The bootstrap installs exactly two signal entry points in `Main.main`, one per user signal:

```java
Signal.handle(
        new Signal("INT"),
        signal -> {
            if (!exchanges.interruptLiveExchanges()) {
                System.exit(ExitCodeMapper.SIGINT);
            }
        });
Signal.handle(
        new Signal("TERM"),
        signal -> {
            if (!exchanges.terminateLiveExchanges()) {
                System.exit(ExitCodeMapper.SIGTERM);
            }
        });
```

Each handler latches its cause on every live context, wait-free, and normal control flow
finishes the work — the command's `awaitUninterruptibly` returns, `execute()` yields the
cause-derived code — and only `Main.main` exits, with that code. When no context is live the
latch has nothing to arm, so the handler terminates the process itself with that signal's
conventional status — the same 130 or 143 a latched run exits by — and no user signal is ever
absorbed. This is a deliberate deviation from the shutdown-hook
design originally considered, forced by native evidence recorded below: on the native runtime
a shutdown hook runs but the process then exits with status 0, and every attempt to influence
the status from inside the shutdown path deadlocks or hangs. The signal-handler latch produces
the conventional 130 and 143 deterministically on both executables, and because the latch is
first-wins, an interrupt followed by a termination keeps the interrupt's status. An
architecture rule pins the containment this buys: `sun.misc` signal machinery may appear only
in `bootstrap.Main`, so the latches stay process composition and neither the library surface
nor any command can grow its own signal handling.

A signal arriving while no exchange is live — before the command line starts, between two
exchanges of a page walk, or after the last one closed — finds no context to latch, so the
handler exits the process itself with the signal's conventional status rather than absorbing
it; an interactive interrupt during startup or a quiet window therefore terminates promptly
with the same status a latched run would produce. Commands do not rely on that fallback for
real work: every command that performs an exchange registers its context for its whole
dispatch window — the single-request spine registers on dispatch, and the answers command
registers its connect window — so a signal during an actual exchange rides the latch and the
mode still renders its failure document.

### Unblock-by-close and the broken-pipe path

Closing the response body's `InputStream` is the only portable way to end a read parked inside
a blocking HTTP stream: the publisher closes the body on every terminal path, so a latch from
any thread ends the reader promptly. On the output side, a `PrintStream` swallows write
failures into its error flag, and a `PrintWriter` over one never sees them; the bootstrap
therefore installs a byte-lossless stdout writer (ISO-8859-1 one-byte-per-character round
trip, an adapter that converts `checkError()` into an `IOException`, and `PrintWriter`
semantics on top) and the run's relay treats `PrintWriter.checkError()` as the broken-pipe
signal: it latches BROKEN_PIPE, cancels the subscription, and the process exits 0 silently.
That write-error path *is* the EPIPE signal path: the JVM ignores SIGPIPE by design, and on the
native runtime SIGPIPE is likewise neutralized by default (evidence below), so both executables
observe the broken pipe as a write failure rather than dying with 141.

Scope note: the ISO-8859-1 writer is today installed once for the whole CLI process in
`bootstrap.Main` and shared by every subcommand, which is byte-lossless for any payload but
leaves character-set policy global. The presentation layer is expected to own per-command
writers when output modes arrive; until then the bootstrap writer remains the single way any
command touches stdout.

### Deadlines: two enforcers, one cause

The body publisher evaluates both deadlines between reads — after every delivered chunk and
while parked awaiting credit — but a reader blocked inside a silent read returns to no
checkpoint, so in-loop checks alone can never fire there. The run therefore also arms
`application.stream.DeadlineWatchdog`, a daemon thread that latches the elapsed cause and
closes the exchange, which is precisely the unblock path for the parked read. Both enforcers
race on the same latch, so the first deadline to elapse decides the cause exactly once — and
the watchdog closes the exchange on every fired deadline whether or not its latch won that
race, because the publisher latches but never closes, and only this close releases the run's
registry entry, reader executor, and transport (closing is idempotent). A deadline that fires
before the run's consumer subscribes therefore closes first and subscribes second: the
publisher answers that late subscription with `onSubscribe` followed by `onError` instead of
throwing out of `subscribe`, so the ending is decided by the latched cause — the documented
exit with its one diagnostic — and never by a crashed subscription call. Both read the same
injected `Clock` seam as the publisher (the watchdog also names its worker thread uniquely per
armed instance, so concurrent runs are distinguishable in thread dumps).

**Caller guidance:** arm both budgets together. The idle budget bounds silence (a stalled
server or a wedged producer) and the wall budget bounds the whole run including healthy
streaming; when both are armed the first to elapse decides the cause, and a wall deadline
strictly smaller than the idle deadline degenerates to a pure total timeout. Treat the
watchdog's ~20 ms tick as the granularity of deadline enforcement, and remember that no
deadline mechanism can interrupt a connect attempt or a silent headers phase — the probe bounds
that opening window on the transport side instead, through the client's connect timeout plus a
request timeout that follows the run's wall budget when one is given and otherwise defaults to
a fixed thirty-second ceiling; a timeout there surfaces as `HttpTimeoutException`, which the
command maps to the transport-failure exit path (6) with its single diagnostic line.

### Executor ownership

The publisher is caller-owned machinery: it schedules exactly one reader task on the executor
it is given and never shuts that executor down. The bravehttp gateway owns the per-exchange
resources — a single-thread daemon reader executor and the `java.net.http.HttpClient` — and
bundles their release with the publisher's own terminal cleanup into the `ProbeStream` close
action, so one `close()` ends everything an exchange created. The watchdog is a daemon thread
owned by the run's relay scope, and the registry entry is owned by the command's
register/detach pair. No non-daemon thread outlives a decided run, so `Main.main`'s exit is
never blocked by streaming machinery.

### Architecture consequence: streaming machinery lives in the application layer

The composition rule (only `bootstrap..` and `api.internal..` may call constructors of concrete
classes living in `adapter..` packages) forbids an adapter from constructing value objects or
helpers in its own package. The streaming pieces the probe composes — the body publisher, the
writer relay, the deadline watchdog — are transport-agnostic (they consume `InputStream`,
`PrintWriter`, and the cancellation latch), so they moved to `application.stream` where the
cancellation context already lived. The bravehttp gateway constructs them freely and adds only
the `java.net.http`-specific glue; the CLI command contributes presentation and run policy
only.

## Evidence

### Signal mechanism investigation (JVM = Liberica 25, native = GraalVM community 25.0.2)

| Mechanism probed | JVM result | Native result |
| --- | --- | --- |
| shutdown hook only, SIGINT | hooks run, natural exit 130 | hooks run, exit **0** |
| hook latches, main then calls `System.exit(130)` | 130 | **deadlock**: process hangs |
| hook calls `Runtime.halt(130)` itself | 130 | **hang** |
| shutdown hook **and** `sun.misc.Signal` INT handler registered | 130 | **hang** (hybrid breaks dispatch) |
| `sun.misc.Signal` INT handler alone, normal flow exits | **130, deterministic** | **130, deterministic** |
| natural SIGTERM (no handler) | 143 | 0 |
| SIGPIPE on closed stdout (`yes` as control) | ignored by JVM, write error | **ignored**: native flood exits 0 while C `yes` dies 141 |

Conclusions: the shutdown-hook mechanism cannot deliver 130 on the native runtime and cannot be
combined with a signal handler there, so the signal-handler latch is the mechanism; SIGPIPE
needs no native-image build argument on this toolchain (no `-R:+EnableSignalHandlers` required —
`sun.misc.Signal` dispatch works by default), and no build-arg change was made. The SIGTERM
divergence recorded above (natural 143 on the JVM, 0 on native with no handler) is RESOLVED:
TERM now rides the same handler mechanism as INT, and the termination scenarios of the answers
and web process suites verify exit 143 — with jsonl's terminal error record and an empty
stderr, and mid-walk beside a pacing wait — on both the JVM launcher and the native binary.

### Process test evidence (both suites green, identical outcomes)

`StreamProbeProcessTest` (JVM launcher from `installDist`) and `StreamProbeNativeSmokeTest`
(native binary from `nativeCompile`) share the scenario implementations in
`testsupport.StreamProbeScenarios` and a `ScriptedSseServer`:

| Scenario | JVM exit | Native exit | Contract verified |
| --- | --- | --- | --- |
| `sigintExits130Naturally` | 130 | 130 | `/bin/kill -INT` after server-confirmed byte flow; stdout byte-exact pre-interrupt payload; empty stderr; server-side closed-connection witness after the probe is gone |
| `epipeExitsZeroSilently` | 0 | 0 | reader closes the probe's stdout pipe after 256 KiB of a 32 MiB stream; no error banner, never 141 |
| `normalCompletionExitsZero` | 0 | 0 | full scripted stream drained; stdout byte-exact including non-ASCII and NUL bytes; empty stderr |
| `idleTimeoutExits6` | 6 | 6 | `--idle-timeout 1s` against a stalled server; exactly one stderr diagnostic naming IDLE_TIMEOUT |
| `abruptCloseExits6WithOneDiagnostic` | 6 | 6 | server cuts the connection mid-body; prompt exit, delivered bytes only, exactly one stderr diagnostic naming TRANSPORT_FAILURE, never a hang |
| `silentHeadersEndWithinTheWallBudgetAs6` | 6 | 6 | server accepts but never sends headers with `--wall-timeout 2s`; prompt exit 6, one stderr diagnostic naming the failed open |
| `zeroOrNegativeDurationIsAUsageErrorBeforeAnyRequest` | 2 | 2 | `--idle-timeout 0s` and `--wall-timeout PT-1S` rejected with usage before any request is observed |
| `nonLoopbackUrlRejected` | 2 | 2 | usage error before any connection; no request recorded by any server |

In-process unit evidence covers the same cause mapping without processes
(`StreamProbeCommandTest`, `ExchangeRegistryTest`, `StreamBodyRelayTest`, the watchdog's
injected-clock seam in `DeadlineWatchdogTest`, and the headers-phase request timeout in
`StreamProbeGatewayTest`), and the publisher's reactive contracts — demand withholding,
cancel-unblocks-stalled-read, both deadlines, subscriber failure including the
negative-request latch, executor ownership — are pinned in `StreamBodyPublisherTest`.

### Backpressure and demand findings

The publisher is cold and single-subscription; delivery follows outstanding request credit
strictly, chunks are at most eight KiB, and a zero-credit reader parks without holding locks.
The probe's relay requests unbounded demand because its only sink is stdout and the races under
test are cancellation races, not slow-consumer ones. The answers semantic layer requests
bounded credit in chunk units: the publisher weighs credit in bytes, so the processor's single
outstanding pull asks for one full chunk (eight KiB) and stays one chunk ahead of semantic
demand — one pull in flight at a time, issued only while downstream demand lives and no
decoded event waits buffered — instead of the one-byte reads a `request(1)` would procure.

## Consequences

- `stream-probe` remains a hidden, credentials-free command and is the reusable instrument for
  re-proving lifecycle behavior whenever the toolchain or the runtime changes.
- Commands that stream treat stdout as a byte sink through the bootstrap's lossless writer and
  never as a `PrintStream` they construct themselves, so the broken-pipe latch stays uniform.
- Deadlines are always armed as the pair (idle, wall) by callers that need bounded runs, with
  the watchdog as the silent-reader safety net and the publisher's in-loop checks as the
  fast path.
- SIGTERM is no longer a divergence: the TERM latch resolves to the conventional 143 on both
  executables, and the exit contract depends on it.
