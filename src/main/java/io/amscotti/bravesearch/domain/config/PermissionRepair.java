package io.amscotti.bravesearch.domain.config;

/**
 * What a permission repair changed: whether the managed directory's mode was reset to {@code
 * 0700}, whether the credential file's mode was tightened to {@code 0600}, and whether a
 * best-effort ACL listing saw entries beyond the owner on the file.
 *
 * <p>Repair restores POSIX modes only, tightening the file and never loosening an
 * already-owner-only mode. Structural violations — symbolic links, foreign ownership, extra
 * hard links — are outside its scope and remain refusals of the config store itself, so a
 * successful repair never claims more safety than it restored. The ACL flag is an advisory,
 * not a repair: macOS ACL entries can only be reviewed and removed by the operator.
 *
 * @param directoryModeChanged whether the managed directory mode was reset
 * @param fileModeChanged whether the credential-file mode was tightened
 * @param nonOwnerAclSuspected whether the best-effort ACL listing saw entries beyond the owner
 */
public record PermissionRepair(
        boolean directoryModeChanged, boolean fileModeChanged, boolean nonOwnerAclSuspected) {}
