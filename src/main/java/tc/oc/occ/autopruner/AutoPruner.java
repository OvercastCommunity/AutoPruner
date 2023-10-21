package tc.oc.occ.autopruner;

import net.querz.mca.Chunk;
import net.querz.mca.MCAFile;
import net.querz.mca.MCAUtil;
import net.querz.mca.Section;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.function.Consumer;
import java.util.logging.Logger;

public interface AutoPruner {
  Logger logger = getLogger();

  static Logger getLogger() {
    System.setProperty("java.util.logging.SimpleFormatter.format",
        "%1$tF %1$tT %4$s %2$s %5$s%6$s%n");
    return Logger.getLogger("AutoPruner");
  }

  /**
   * Prune MCA File using class logger
   *
   * @param filePath
   */
  static void pruneMCAFileLogger(String filePath) {
    pruneMCAFile(filePath, logger::info, logger::warning);
  }

  /**
   * Recursively prune directories using class logger
   *
   * @param file
   * @param depth
   * @return
   */
  static long recursivelyProcessFiles(File file, long depth) {
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
  static long recursivelyProcessFiles(File file, long depth, Consumer<String> logging) {
    return recursivelyProcessFiles(file, depth, logging, logging);
  }

  /**
   * Recursively prune directories using consumers to log
   *
   * @param file
   * @param depth
   * @param infoLogging
   * @param warnLogging
   * @return bytes removed
   */
  static long recursivelyProcessFiles(File file, long depth, Consumer<String> infoLogging, Consumer<String> warnLogging) {
    long sizeDeleted = 0;
    if (depth > 30) {
      return 0;
    }
    File[] files = file.listFiles();
    if (files != null) {
      for (File childFile : files) {
        if (childFile.isDirectory()) {
          sizeDeleted += recursivelyProcessFiles(childFile, depth, infoLogging, warnLogging);
        } else if (childFile.isFile() && childFile.getName().endsWith(".mca")) {
          sizeDeleted += pruneMCAFile(childFile.getAbsolutePath(), infoLogging, warnLogging);
        }
      }
    }
    return sizeDeleted;
  }

  /**
   * Prune without logging
   *
   * @param path
   * @return bytes removed
   */
  static long pruneMCAFile(String path) {
    return pruneMCAFile(path, (message) -> {
    }, (message) -> {
    });
  }

  /**
   * Prune MCA File and using the provided consumers to log
   *
   * @param path
   * @param infoLogging consumer
   * @param warnLogging consumer
   * @return bytes removed
   */
  static long pruneMCAFile(String path, Consumer<String> infoLogging, Consumer<String> warnLogging) {
    long sizeChange = 0;
    boolean actionTaken = false;
    try {
      File regionFile = new File(path);
      long initialSize = regionFile.length();
      MCAFile mcaFile = MCAUtil.read(regionFile);
      boolean regionFileEmpty = true;

      for (int i = 0; i < 1024; i++) {
        Chunk chunk = mcaFile.getChunk(i);

        if (chunk == null) continue;

        if (chunk.changesMade()) {
          actionTaken = true;
        }

        ListTag<CompoundTag> entities = chunk.getEntities();
        if (entities != null && entities.size() > 0) {
          regionFileEmpty = false;
          continue;
        }

        ListTag<CompoundTag> tileEntities = chunk.getTileEntities();
        if (tileEntities != null && tileEntities.size() > 0) {
          regionFileEmpty = false;
          continue;
        }

        boolean empty = true;

        for (int y = 0; y < 16; y++) {
          Section section = chunk.getSection(y);
          if (section != null && !section.isEmpty()) {
            empty = false;
            regionFileEmpty = false;
            break;
          }
        }

        if (empty && !chunk.hasSpecialBiomes()) {
          mcaFile.setChunk(i, null);
          actionTaken = true;
        }
      }
      if (regionFileEmpty) {
        Files.deleteIfExists(Paths.get(path));
        String deleteMessage = "Deleted file (" + readableFileSize(initialSize) + ") : " + path;
        infoLogging.accept(deleteMessage);
        sizeChange = initialSize;
      } else if (actionTaken) {
        MCAUtil.write(mcaFile, path);
        long newSize = regionFile.length();
        sizeChange = initialSize - newSize;
        String deleteMessage = "Deleted " + readableFileSize(sizeChange) + " from: " + path;
        infoLogging.accept(deleteMessage);
      } else {
        // Avoid unnecessary disk writes
        infoLogging.accept("Skipping already Pruned File: " + path);
      }
    } catch (Exception e) {
      String warnMessage = "Failed to parse file: " + path + ", " + e.getMessage();
      warnLogging.accept(warnMessage);
    }
    return sizeChange;
  }

  /**
   * @param size in bytes
   * @return Human readable file size String
   */
  static String readableFileSize(long size) {
    if (size <= 0) return "0";
    final String[] units = new String[]{"B", "kB", "MB", "GB", "TB"};
    int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
    return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
  }

}
