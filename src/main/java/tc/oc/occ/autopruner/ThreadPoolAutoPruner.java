package tc.oc.occ.autopruner;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class ThreadPoolAutoPruner {
  private final ExecutorService threadPoolExecutor;
  private final Logger logger = AutoPruner.logger;

  public ThreadPoolAutoPruner(int threadCount) {
    this.threadPoolExecutor = Executors.newFixedThreadPool(threadCount);
  }

  /**
   * Recursively prunes a directory tree across the thread pool, logging through the shared logger.
   *
   * @return bytes removed
   */
  public long recursivelyProcessFiles(File file, long depth) throws ExecutionException, InterruptedException {
    return recursivelyProcessFiles(file, depth, false);
  }

  /**
   * Recursively prunes (or, when {@code dryRun}, only previews) a directory tree across the thread
   * pool, logging through the shared logger.
   *
   * @return bytes removed
   */
  public long recursivelyProcessFiles(File file, long depth, boolean dryRun) throws ExecutionException, InterruptedException {
    PruneSummary summary = new PruneSummary();
    long sizeDeleted = 0;
    for (Future<Long> future : recursivelyProcessFilesInternal(file, depth, logger::info, logger::warning, dryRun, summary)) {
      sizeDeleted += future.get();
    }
    logger.info((dryRun ? "Would delete " : "Deleted ") + AutoPruner.readableFileSize(sizeDeleted) + " from: " + file.getAbsolutePath());
    if (summary.changedFiles() >= AutoPruner.SUMMARY_THRESHOLD) {
      logger.info(System.lineSeparator() + summary.format(dryRun));
    }
    return sizeDeleted;
  }

  /** @return bytes removed */
  public long recursivelyProcessFiles(File file, long depth, Consumer<String> logging, Consumer<String> warnLogging) throws ExecutionException, InterruptedException {
    return recursivelyProcessFiles(file, depth, logging, warnLogging, false);
  }

  /** @return bytes removed */
  public long recursivelyProcessFiles(File file, long depth, Consumer<String> logging, Consumer<String> warnLogging, boolean dryRun) throws ExecutionException, InterruptedException {
    long sizeDeleted = 0;
    for (Future<Long> future : recursivelyProcessFilesInternal(file, depth, logging, warnLogging, dryRun, null)) {
      sizeDeleted += future.get();
    }
    return sizeDeleted;
  }

  private List<Future<Long>> recursivelyProcessFilesInternal(
      File file,
      long depth,
      Consumer<String> infoLogging,
      Consumer<String> warnLogging,
      boolean dryRun,
      PruneSummary summary) {
    if (depth > AutoPruner.MAX_RECURSION_DEPTH) {
      return Collections.emptyList();
    }
    List<Future<Long>> futures = new ArrayList<>();
    File[] files = file.listFiles();
    if (files != null) {
      for (File childFile : files) {
        if (childFile.isDirectory()) {
          futures.addAll(recursivelyProcessFilesInternal(childFile, depth + 1, infoLogging, warnLogging, dryRun, summary));
        } else if (childFile.isFile() && childFile.getName().endsWith(".mca")) {
          Callable<Long> callable = () -> AutoPruner.pruneMCAFile(childFile.getAbsolutePath(), infoLogging, warnLogging, dryRun, summary);
          futures.add(threadPoolExecutor.submit(callable));
        }
      }
    }
    return futures;
  }

  public void close() {
    threadPoolExecutor.shutdownNow();
  }
}
