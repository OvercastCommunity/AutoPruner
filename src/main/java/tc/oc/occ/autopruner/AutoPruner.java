package tc.oc.occ.autopruner;

import net.querz.mca.Chunk;
import net.querz.mca.MCAFile;
import net.querz.mca.MCAUtil;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.function.Consumer;
import java.util.logging.Logger;

public interface AutoPruner {

  /** Shared by every prune entry point, including {@link ThreadPoolAutoPruner}. */
  Logger logger = getLogger();

  int MAX_RECURSION_DEPTH = 30;

  /** A directory run prints a per-era summary once at least this many files have been changed. */
  int SUMMARY_THRESHOLD = 10;

  static Logger getLogger() {
    System.setProperty("java.util.logging.SimpleFormatter.format",
        "%1$tF %1$tT %4$s %2$s %5$s%6$s%n");
    return Logger.getLogger("AutoPruner");
  }

  /** Prunes a single region file, logging through the shared logger. */
  static void pruneMCAFileLogger(String filePath) {
    pruneMCAFileLogger(filePath, false);
  }

  /** Prunes (or, when {@code dryRun}, only previews) a single region file via the shared logger. */
  static void pruneMCAFileLogger(String filePath, boolean dryRun) {
    pruneMCAFile(filePath, logger::info, logger::warning, dryRun, null);
  }

  /** Recursively prunes a directory tree, logging through the shared logger. */
  static long recursivelyProcessFiles(File file, long depth) {
    long sizeDeleted = recursivelyProcessFiles(file, depth, logger::info, logger::warning, false, null);
    logger.info("Deleted " + readableFileSize(sizeDeleted) + " from: " + file.getAbsolutePath());
    return sizeDeleted;
  }

  /**
   * Recursively prunes (or, when {@code dryRun}, only previews) a directory tree via the shared
   * logger, printing a per-era summary once {@link #SUMMARY_THRESHOLD} files have changed.
   */
  static long recursivelyProcessFiles(File file, long depth, boolean dryRun) {
    PruneSummary summary = new PruneSummary();
    long sizeDeleted = recursivelyProcessFiles(file, depth, logger::info, logger::warning, dryRun, summary);
    logger.info((dryRun ? "Would delete " : "Deleted ") + readableFileSize(sizeDeleted) + " from: " + file.getAbsolutePath());
    if (summary.changedFiles() >= SUMMARY_THRESHOLD) {
      logger.info(System.lineSeparator() + summary.format(dryRun));
    }
    return sizeDeleted;
  }

  /** Recursively prunes a directory tree, sending info and warnings to one consumer. */
  static long recursivelyProcessFiles(File file, long depth, Consumer<String> logging) {
    return recursivelyProcessFiles(file, depth, logging, logging, false, null);
  }

  /** @return bytes removed */
  static long recursivelyProcessFiles(File file, long depth, Consumer<String> infoLogging, Consumer<String> warnLogging) {
    return recursivelyProcessFiles(file, depth, infoLogging, warnLogging, false, null);
  }

  /** @return bytes removed */
  static long recursivelyProcessFiles(File file, long depth, Consumer<String> infoLogging, Consumer<String> warnLogging, boolean dryRun) {
    return recursivelyProcessFiles(file, depth, infoLogging, warnLogging, dryRun, null);
  }

  /**
   * Recursively prunes every {@code .mca} file under {@code file}. When {@code dryRun} is set no
   * file is modified or deleted; the would-be actions are logged instead. Per-file outcomes are
   * recorded into {@code summary} when it is non-null.
   *
   * @return bytes removed
   */
  static long recursivelyProcessFiles(File file, long depth, Consumer<String> infoLogging, Consumer<String> warnLogging, boolean dryRun, PruneSummary summary) {
    if (depth > MAX_RECURSION_DEPTH) {
      return 0;
    }
    long sizeDeleted = 0;
    File[] files = file.listFiles();
    if (files != null) {
      for (File childFile : files) {
        if (childFile.isDirectory()) {
          sizeDeleted += recursivelyProcessFiles(childFile, depth + 1, infoLogging, warnLogging, dryRun, summary);
        } else if (childFile.isFile() && childFile.getName().endsWith(".mca")) {
          sizeDeleted += pruneMCAFile(childFile.getAbsolutePath(), infoLogging, warnLogging, dryRun, summary);
        }
      }
    }
    return sizeDeleted;
  }

  /**
   * Locates and reads the sibling entity region for a block region file. In modern worlds
   * (1.17+) entities are stored under {@code entities/r.X.Z.mca} next to the block
   * {@code region/r.X.Z.mca}. Returns {@code null} when the file is not laid out that way or the
   * sibling cannot be read.
   */
  static MCAFile readSiblingEntityRegion(File regionFile) {
    File regionDir = regionFile.getParentFile();
    if (regionDir == null || !"region".equals(regionDir.getName())) {
      return null;
    }
    File worldDir = regionDir.getParentFile();
    if (worldDir == null) {
      return null;
    }
    File entityFile = new File(new File(worldDir, "entities"), regionFile.getName());
    if (!entityFile.isFile()) {
      return null;
    }
    try {
      return MCAUtil.read(entityFile);
    } catch (Exception e) {
      return null; // Best effort: treat an unreadable entity region as no entity data.
    }
  }

  /** Prunes a single region file without logging; returns bytes removed. */
  static long pruneMCAFile(String path) {
    return pruneMCAFile(path, message -> { }, message -> { }, false, null);
  }

  /** @return bytes removed */
  static long pruneMCAFile(String path, Consumer<String> infoLogging, Consumer<String> warnLogging) {
    return pruneMCAFile(path, infoLogging, warnLogging, false, null);
  }

  /** @return bytes removed */
  static long pruneMCAFile(String path, Consumer<String> infoLogging, Consumer<String> warnLogging, boolean dryRun) {
    return pruneMCAFile(path, infoLogging, warnLogging, dryRun, null);
  }

  /**
   * Prunes a single region file, removing empty chunks and deleting the file once it is empty.
   * When {@code dryRun} is set the file is left untouched and the would-be action is logged. The
   * outcome is recorded into {@code summary} when it is non-null.
   *
   * @return bytes removed
   */
  static long pruneMCAFile(String path, Consumer<String> infoLogging, Consumer<String> warnLogging, boolean dryRun, PruneSummary summary) {
    long sizeChange = 0;
    boolean actionTaken = false;
    int removedChunks = 0;
    try {
      File regionFile = new File(path);
      long initialSize = regionFile.length();
      MCAFile mcaFile = MCAUtil.read(regionFile);
      // From 1.17 onwards entities live in a sibling "entities" region; consult it so we never
      // prune a block chunk that still has entity data stored alongside it.
      MCAFile entityRegion = readSiblingEntityRegion(regionFile);
      boolean regionFileEmpty = true;
      Chunk versionSample = null;
      boolean mixedVersions = false;

      for (int i = 0; i < MCAFile.CHUNK_COUNT; i++) {
        Chunk chunk = mcaFile.getChunk(i);
        if (chunk == null) {
          continue;
        }
        if (versionSample == null) {
          versionSample = chunk;
        } else if (chunk.getDataVersion() != versionSample.getDataVersion()) {
          mixedVersions = true;
        }
        if (chunk.changesMade()) {
          actionTaken = true;
        }
        Chunk entityChunk = entityRegion == null ? null : entityRegion.getChunk(i);
        if (chunk.hasContent(entityChunk)) {
          regionFileEmpty = false;
        } else {
          mcaFile.setChunk(i, null);
          actionTaken = true;
          removedChunks++;
        }
      }

      String era = versionSample == null ? "empty region file" : versionSample.versionEra();
      String version = versionSample == null ? ""
          : " (" + versionSample.describeVersion() + (mixedVersions ? ", mixed" : "") + ")";

      if (regionFileEmpty) {
        if (!dryRun) {
          Files.deleteIfExists(Paths.get(path));
        }
        sizeChange = initialSize;
        if (summary != null) {
          summary.record(era, PruneSummary.Outcome.DELETED, removedChunks, sizeChange);
        }
        infoLogging.accept((dryRun ? "Would delete file (" : "Deleted file (") + readableFileSize(initialSize) + ") : " + path + version);
      } else if (actionTaken) {
        if (!dryRun) {
          MCAUtil.write(mcaFile, path);
          sizeChange = initialSize - regionFile.length();
        }
        if (summary != null) {
          summary.record(era, PruneSummary.Outcome.PRUNED, removedChunks, sizeChange);
        }
        if (dryRun) {
          infoLogging.accept("Would prune " + removedChunks + " empty chunk(s) from: " + path + version);
        } else {
          infoLogging.accept("Deleted " + readableFileSize(sizeChange) + " from: " + path + version);
        }
      } else {
        if (summary != null) {
          summary.record(era, PruneSummary.Outcome.SKIPPED, 0, 0);
        }
        infoLogging.accept("Skipping already Pruned File: " + path + version);
      }
    } catch (Exception e) {
      warnLogging.accept("Failed to parse file: " + path + ", " + e.getMessage());
    }
    return sizeChange;
  }

  /**
   * @param size in bytes
   * @return human-readable file size
   */
  static String readableFileSize(long size) {
    if (size <= 0) {
      return "0";
    }
    final String[] units = {"B", "kB", "MB", "GB", "TB"};
    int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
    return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
  }
}
