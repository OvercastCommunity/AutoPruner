package net.querz.mca;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Iterator;

public class MCAFile implements Iterable<Chunk> {

  private final int regionX;
  private final int regionZ;
  private Chunk[] chunks;

  /**
   * MCAFile represents a world save file used by Minecraft to store world
   * data on the hard drive.
   * This constructor needs the x- and z-coordinates of the stored region,
   * which can usually be taken from the file name {@code r.x.z.mca}
   *
   * @param regionX The x-coordinate of this region.
   * @param regionZ The z-coordinate of this region.
   */
  public MCAFile(int regionX, int regionZ) {
    this.regionX = regionX;
    this.regionZ = regionZ;
  }

  /**
   * Calculates the index of a chunk from its x- and z-coordinates in this region.
   * This works with absolute and relative coordinates.
   *
   * @param chunkX The x-coordinate of the chunk.
   * @param chunkZ The z-coordinate of the chunk.
   * @return The index of this chunk.
   */
  public static int getChunkIndex(int chunkX, int chunkZ) {
    return (chunkX & 0x1F) + (chunkZ & 0x1F) * 32;
  }

  /**
   * Reads an .mca file from a {@code RandomAccessFile} into this object.
   * This method does not perform any cleanups on the data.
   *
   * @param raf The {@code RandomAccessFile} to read from.
   * @throws IOException If something went wrong during deserialization.
   */
  public void deserialize(RandomAccessFile raf) throws IOException {
    chunks = new Chunk[1024];
    for (int i = 0; i < 1024; i++) {
      raf.seek(i * 4);
      int offset = raf.read() << 16;
      offset |= (raf.read() & 0xFF) << 8;
      offset |= raf.read() & 0xFF;
      if (raf.readByte() == 0) {
        continue;
      }
      raf.seek(4096 + i * 4);
      int timestamp = raf.readInt();
      Chunk chunk = new Chunk(timestamp);
      raf.seek(4096L * offset + 4); //+4: skip data size
      chunk.deserialize(raf);
      chunks[i] = chunk;
    }
  }

  /**
   * Serializes this object to an .mca file.
   * This method does not perform any cleanups on the data.
   *
   * @param raf              The {@code RandomAccessFile} to write to.
   * @param changeLastUpdate Whether it should update all timestamps that show
   *                         when this file was last updated.
   * @return The amount of chunks written to the file.
   * @throws IOException If something went wrong during serialization.
   */
  public int serialize(RandomAccessFile raf, boolean changeLastUpdate) throws IOException {
    int globalOffset = 2;
    int lastWritten = 0;
    int timestamp = (int) (System.currentTimeMillis() / 1000L);
    int chunksWritten = 0;
    int chunkXOffset = MCAUtil.regionToChunk(regionX);
    int chunkZOffset = MCAUtil.regionToChunk(regionZ);

    if (chunks == null) {
      return 0;
    }

    for (int cx = 0; cx < 32; cx++) {
      for (int cz = 0; cz < 32; cz++) {
        int index = getChunkIndex(cx, cz);
        Chunk chunk = chunks[index];
        if (chunk == null) {
          continue;
        }
        raf.seek(4096L * globalOffset);
        lastWritten = chunk.serialize(raf, chunkXOffset + cx, chunkZOffset + cz);

        if (lastWritten == 0) {
          continue;
        }

        chunksWritten++;

        int sectors = (lastWritten >> 12) + (lastWritten % 4096 == 0 ? 0 : 1);

        raf.seek(index * 4L);
        raf.writeByte(globalOffset >>> 16);
        raf.writeByte(globalOffset >> 8 & 0xFF);
        raf.writeByte(globalOffset & 0xFF);
        raf.writeByte(sectors);

        // write timestamp
        raf.seek(index * 4L + 4096);
        raf.writeInt(changeLastUpdate ? timestamp : chunk.getLastMCAUpdate());

        globalOffset += sectors;
      }
    }

    // padding
    if (lastWritten % 4096 != 0) {
      raf.seek(globalOffset * 4096L - 1);
      raf.write(0);
    }
    return chunksWritten;
  }

  /**
   * Set a specific Chunk at a specific index. The index must be in range of 0 - 1023.
   *
   * @param index The index of the Chunk.
   * @param chunk The Chunk to be set.
   * @throws IndexOutOfBoundsException If index is not in the range.
   */
  public void setChunk(int index, Chunk chunk) {
    checkIndex(index);
    if (chunks == null) {
      chunks = new Chunk[1024];
    }
    chunks[index] = chunk;
  }

  /**
   * Returns the chunk data of a chunk at a specific index in this file.
   *
   * @param index The index of the chunk in this file.
   * @return The chunk data.
   */
  public Chunk getChunk(int index) {
    checkIndex(index);
    if (chunks == null) {
      return null;
    }
    return chunks[index];
  }

  private int checkIndex(int index) {
    if (index < 0 || index > 1023) {
      throw new IndexOutOfBoundsException();
    }
    return index;
  }

  @Override
  public Iterator<Chunk> iterator() {
    return Arrays.stream(chunks).iterator();
  }
}
