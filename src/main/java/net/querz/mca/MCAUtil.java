package net.querz.mca;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides main and utility functions to read and write .mca files and
 * to convert block, chunk and region coordinates.
 */
public final class MCAUtil {

  private static final Pattern mcaFilePattern = Pattern.compile("^.*r\\.(?<regionX>-?\\d+)\\.(?<regionZ>-?\\d+)\\.mca$");

  /**
   * Reads an MCA file and loads all of its chunks.
   *
   * @param file The file to read the data from.
   * @return An in-memory representation of the MCA file with decompressed chunk data
   * @throws IOException if something during deserialization goes wrong.
   */
  public static MCAFile read(File file) throws IOException {
    MCAFile mcaFile = newMCAFile(file);
    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      mcaFile.deserialize(raf);
      return mcaFile;
    }
  }

  /**
   * Calls {@link MCAUtil#write(MCAFile, File, boolean)} without changing the timestamps.
   *
   * @param file    The file to write to.
   * @param mcaFile The data of the MCA file to write.
   * @return The amount of chunks written to the file.
   * @throws IOException If something goes wrong during serialization.
   * @see MCAUtil#write(MCAFile, File, boolean)
   */
  public static int write(MCAFile mcaFile, String file) throws IOException {
    return write(mcaFile, new File(file), false);
  }

  /**
   * Writes an {@code MCAFile} object to disk. It optionally adjusts the timestamps
   * when the file was last saved to the current date and time or leaves them at
   * the value set by either loading an already existing MCA file or setting them manually.<br>
   * If the file already exists, it is completely overwritten by the new file (no modification).
   *
   * @param file             The file to write to.
   * @param mcaFile          The data of the MCA file to write.
   * @param changeLastUpdate Whether to adjust the timestamps of when the file was saved.
   * @return The amount of chunks written to the file.
   * @throws IOException If something goes wrong during serialization.
   */
  public static int write(MCAFile mcaFile, File file, boolean changeLastUpdate) throws IOException {
    File to = file;
    if (file.exists()) {
      to = File.createTempFile(to.getName(), null);
    }
    int chunks;
    try (RandomAccessFile raf = new RandomAccessFile(to, "rw")) {
      chunks = mcaFile.serialize(raf, changeLastUpdate);
    }

    if (chunks > 0 && to != file) {
      Files.move(to.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    return chunks;
  }

  /**
   * Turns a region coordinate value into a chunk coordinate value.
   *
   * @param region The region coordinate value.
   * @return The chunk coordinate value.
   */
  public static int regionToChunk(int region) {
    return region << 5;
  }

  public static MCAFile newMCAFile(File file) {
    Matcher m = mcaFilePattern.matcher(file.getName());
    if (m.find()) {
      return new MCAFile(Integer.parseInt(m.group("regionX")), Integer.parseInt(m.group("regionZ")));
    }
    throw new IllegalArgumentException("invalid mca file name: " + file.getName());
  }
}
