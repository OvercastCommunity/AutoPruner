package tc.oc.occ.autopruner;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thread-safe tally of prune outcomes, grouped by world-format era. Populated as each region file is
 * processed and rendered into the summary block printed after a directory run. Safe to share across
 * the {@link ThreadPoolAutoPruner} worker threads.
 */
public final class PruneSummary {

  /** What happened to a single region file. */
  public enum Outcome { SKIPPED, COMPACTED, PRUNED, DELETED }

  private static final class EraTally {
    long files;
    long skipped;
    long compacted;
    long pruned;
    long deleted;
    long chunksRemoved;
    long bytesReclaimed;
  }

  private final Map<String, EraTally> tallies = new LinkedHashMap<>();

  synchronized void record(String era, Outcome outcome, long chunksRemoved, long bytesReclaimed) {
    EraTally tally = tallies.computeIfAbsent(era, key -> new EraTally());
    tally.files++;
    tally.chunksRemoved += chunksRemoved;
    tally.bytesReclaimed += bytesReclaimed;
    switch (outcome) {
      case SKIPPED:
        tally.skipped++;
        break;
      case COMPACTED:
        tally.compacted++;
        break;
      case PRUNED:
        tally.pruned++;
        break;
      case DELETED:
        tally.deleted++;
        break;
    }
  }

  /** Region files that changed on disk — compacted, pruned, or deleted (the count a summary threshold is compared against). */
  public synchronized long changedFiles() {
    long changed = 0;
    for (EraTally tally : tallies.values()) {
      changed += tally.compacted + tally.pruned + tally.deleted;
    }
    return changed;
  }

  /** Renders a multi-line summary block, one row per era plus a total. */
  public synchronized String format(boolean dryRun) {
    long totalFiles = 0;
    long totalSkipped = 0;
    long totalCompacted = 0;
    long totalPruned = 0;
    long totalDeleted = 0;
    long totalChunks = 0;
    long totalBytes = 0;

    StringBuilder out = new StringBuilder();
    out.append(dryRun ? "Summary (dry run, no files modified):" : "Summary:");
    for (Map.Entry<String, EraTally> entry : tallies.entrySet()) {
      EraTally tally = entry.getValue();
      out.append(System.lineSeparator()).append("  ").append(formatRow(entry.getKey(), tally));
      totalFiles += tally.files;
      totalSkipped += tally.skipped;
      totalCompacted += tally.compacted;
      totalPruned += tally.pruned;
      totalDeleted += tally.deleted;
      totalChunks += tally.chunksRemoved;
      totalBytes += tally.bytesReclaimed;
    }

    EraTally total = new EraTally();
    total.files = totalFiles;
    total.skipped = totalSkipped;
    total.compacted = totalCompacted;
    total.pruned = totalPruned;
    total.deleted = totalDeleted;
    total.chunksRemoved = totalChunks;
    total.bytesReclaimed = totalBytes;
    out.append(System.lineSeparator()).append("  ").append(formatRow("Total", total));
    return out.toString();
  }

  private static String formatRow(String label, EraTally tally) {
    return String.format(
        "%-26s %d files (%d skipped, %d compacted, %d pruned, %d deleted), %d chunks removed, %s reclaimed",
        label, tally.files, tally.skipped, tally.compacted, tally.pruned, tally.deleted,
        tally.chunksRemoved, AutoPruner.readableFileSize(tally.bytesReclaimed));
  }
}
