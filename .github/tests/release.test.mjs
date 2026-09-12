import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { Script } from 'node:vm';

const workflow = readFileSync(new URL('../workflows/release.yml', import.meta.url), 'utf8');
const scriptBlock = workflow.match(/^ {10}script: \|\n((?: {12}.*\n|\n)+)/m);
assert.ok(scriptBlock, 'the release workflow contains its tag resolver script');
const resolveTag = new Script(`(async (github, context, core) => {
${scriptBlock[1].replace(/^ {12}/gm, '')}
})`).runInNewContext();

const tagSha = 'a'.repeat(40);
const commitSha = 'b'.repeat(40);
const requiredChecks = [
  'jvm-check (macos-26)',
  'jvm-check (ubuntu-24.04)',
  'jvm-check (ubuntu-24.04-arm)',
  'repository hygiene',
  'native-smoke (macos-26)',
  'native-smoke (ubuntu-24.04)',
  'native-smoke (ubuntu-24.04-arm)',
  'dependency lock state and artifact verification',
];

function fixture({ sha = commitSha, type = 'tag', targetType = 'commit', verified = true,
  actor = 'amscotti', author = 'amscotti', failedCheck } = {}) {
  const outputs = new Map();
  const checkedRefs = [];
  const context = {
    ref: 'refs/tags/v1.2.3', sha, actor,
    repo: { owner: 'amscotti', repo: 'brave-search-cli' },
  };
  const listForRef = () => assert.fail('check runs must be paginated');
  const github = {
    rest: {
      git: {
        getRef: async ({ ref }) => {
          assert.equal(ref, 'tags/v1.2.3');
          return { data: { object: { type, sha: tagSha } } };
        },
        getTag: async ({ tag_sha }) => {
          assert.equal(tag_sha, tagSha);
          return { data: {
            object: { type: targetType, sha: commitSha },
            verification: { verified, reason: verified ? 'valid' : 'unsigned' },
          } };
        },
        // Git database commits contain raw author metadata, without an account login.
        getCommit: async () => ({ data: { author: { name: 'Release Maintainer' } } }),
      },
      repos: {
        getCommit: async ({ ref }) => {
          assert.equal(ref, commitSha, 'the account lookup uses the peeled commit');
          return { data: { author: author === null ? null : { login: author } } };
        },
      },
      checks: { listForRef },
    },
    paginate: async (method, { ref }) => {
      assert.equal(method, listForRef);
      checkedRefs.push(ref);
      return requiredChecks.map(name => ({
        name, status: 'completed', conclusion: name === failedCheck ? 'failure' : 'success',
      }));
    },
  };
  return {
    outputs, checkedRefs,
    run: () => resolveTag(github, context, { setOutput: (name, value) => outputs.set(name, value) }),
  };
}

test('a verified annotated tag releases its workflow commit after all CI checks succeed', async () => {
  const release = fixture();
  await release.run();
  assert.equal(release.outputs.get('version'), '1.2.3');
  assert.deepEqual(release.checkedRefs, [commitSha]);
});

test('a moved tag cannot authorize a different workflow commit', async () => {
  const release = fixture({ sha: 'c'.repeat(40) });
  await assert.rejects(release.run(), /not the commit this workflow run was pushed for/);
  assert.equal(release.outputs.size, 0);
  assert.deepEqual(release.checkedRefs, []);
});

test('the tag object hash cannot substitute for the workflow commit', async () => {
  await assert.rejects(fixture({ sha: tagSha }).run(), /not the commit this workflow run was pushed for/);
});

test('a release tag must point directly to a commit', async () => {
  await assert.rejects(fixture({ targetType: 'tag' }).run(), /must point directly to a commit/);
});

test('lightweight tags are rejected', async () => {
  await assert.rejects(fixture({ type: 'commit' }).run(), /lightweight tags are rejected/);
});

test('unverified signatures are rejected', async () => {
  await assert.rejects(fixture({ verified: false }).run(), /signature is not verified/);
});

test('the tag pusher must be a release maintainer', async () => {
  await assert.rejects(fixture({ actor: 'contributor' }).run(), /tag pusher .* is not on the release maintainer allowlist/);
});

test('the commit author must be associated with a release maintainer account', async () => {
  for (const author of ['contributor', null]) {
    await assert.rejects(fixture({ author }).run(), /tag-target commit author .* is not on the release maintainer allowlist/);
  }
});

test('a failed required CI check prevents release authorization', async () => {
  const release = fixture({ failedCheck: 'jvm-check (ubuntu-24.04)' });
  await assert.rejects(release.run(), /required CI check is not green/);
  assert.equal(release.outputs.size, 0);
});
