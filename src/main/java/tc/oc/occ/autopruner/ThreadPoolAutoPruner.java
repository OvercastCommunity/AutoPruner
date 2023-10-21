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
  private final Logger logger;

  public ThreadPoolAutoPruner(int threadCount) {
    this.threadPoolExecutor = Executors.newFixedThreadPool(threadCount);
    System.setProperty("java.util.logging.SimpleFormatter.format",
        "%1$tF %1$tT %4$s %2$s %5$s%6$s%n");
    logger = Logger.getLogger("AutoPruner");
  }

  /**
   * Recursively prune directories using consumer to log
   *
   * @param file
   * @param depth
   * @return bytes removed
   */
  public long recursivelyProcessFiles(File file, long depth) throws ExecutionException, InterruptedException {
    long sizeDeleted = recursivelyProcessFiles(file, depth, logger::info, logger::warning);
    logger.info("Deleted " + AutoPruner.readableFileSize(sizeDeleted) + " from: " + file.getAbsolutePath());
    return sizeDeleted;
  }

  /**
   * Recursively prune directories using consumer to log
   *
   * @param file
   * @param depth
   * @param logging
   * @return bytes removed
   */
  public long recursivelyProcessFiles(File file, long depth, Consumer<String> logging, Consumer<String> warnLogging) throws ExecutionException, InterruptedException {
    List<Future<Long>> futures = recursivelyProcessFilesInternal(file, depth, logging, warnLogging);

    long sizeDeleted = 0;

    for (Future<Long> future : futures) {
      sizeDeleted += future.get();
    }

    return sizeDeleted;
  }

  private List<Future<Long>> recursivelyProcessFilesInternal(
      File file,
      long depth,
      Consumer<String> infoLogging,
      Consumer<String> warnLogging) {
    if (depth > 30) {
      return Collections.emptyList();
    }
    List<Future<Long>> futures = new ArrayList<>();
    File[] files = file.listFiles();
    if (files != null) {
      for (File childFile : files) {
        if (childFile.isDirectory()) {
          futures.addAll(recursivelyProcessFilesInternal(childFile, depth, infoLogging, warnLogging));
        } else if (childFile.isFile() && childFile.getName().endsWith(".mca")) {
          Callable<Long> callable = () -> AutoPruner.pruneMCAFile(childFile.getAbsolutePath(), infoLogging, warnLogging);
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
