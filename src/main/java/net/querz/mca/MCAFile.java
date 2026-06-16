package net.querz.mca;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Iterator;

public class MCAFile implements Iterable<Chunk> {

  /** Number of chunks in a region: a 32x32 grid. */
  public static final int CHUNK_COUNT = 1024;

  private final int regionX;
  private final int regionZ;
  private Chunk[] chunks;
  private long reclaimableSectors;

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
    chunks = new Chunk[CHUNK_COUNT];
    for (int i = 0; i < CHUNK_COUNT; i++) {
      raf.seek(i * 4);
      int b0 = raf.read();
      int b1 = raf.read();
      int b2 = raf.read();
      int sectorCount = raf.read();
      if (sectorCount < 1) {
        continue; // unused chunk slot, or a truncated/empty header
      }
      int offset = (b0 << 16) | ((b1 & 0xFF) << 8) | (b2 & 0xFF);
      raf.seek(4096 + i * 4);
      int timestamp = raf.readInt();
      Chunk chunk = new Chunk(timestamp);
      raf.seek(4096L * offset + 4); //+4: skip data size
      chunk.deserialize(raf);
      chunks[i] = chunk;
    }
  }

  /**
   * Reads an .mca file from a {@code RandomAccessFile} into this object.
   * This method does not perform any cleanups on the data.
   *
   * @param inputStream The {@code ByteArrayInputStream} to read from.
   * @throws IOException If something went wrong during deserialization.
   */
  public void deserialize(ByteArrayInputStream inputStream) throws IOException {
    chunks = new Chunk[CHUNK_COUNT];
    for (int i = 0; i < CHUNK_COUNT; i++) {
      inputStream.reset();
      inputStream.skip(i * 4);
      int b0 = inputStream.read();
      int b1 = inputStream.read();
      int b2 = inputStream.read();
      int sectorCount = inputStream.read();
      if (sectorCount < 1) {
        continue; // unused chunk slot, or a truncated/empty header
      }
      int offset = (b0 << 16) | ((b1 & 0xFF) << 8) | (b2 & 0xFF);
      inputStream.reset();
      inputStream.skip(4096 + i * 4);
      int ch1 = inputStream.read();
      int ch2 = inputStream.read();
      int ch3 = inputStream.read();
      int ch4 = inputStream.read();
      int timestamp = ((ch1 << 24) + (ch2 << 16) + (ch3 << 8) + (ch4));
      Chunk chunk = new Chunk(timestamp);
      inputStream.reset();
      inputStream.skip(4096L * offset + 4); //+4: skip data size
      chunk.deserialize(inputStream);
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
        lastWritten = chunk.serialize(raf);

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
   * Measures, from the raw file bytes, how many 4 KB sectors the file wastes versus a tightly
   * packed layout: gaps between chunks, trailing padding, or slots allocated more sectors than
   * their data needs. A rewrite reclaims this space. Since {@link #serialize} packs chunks
   * back-to-back, an already-compacted file reports zero here, keeping the prune idempotent.
   */
  void analyzeLayout(byte[] raw) {
    long fileSectors = (raw.length + 4095) / 4096;
    long neededSectors = 2; // location + timestamp header tables
    for (int i = 0; i < CHUNK_COUNT; i++) {
      int entry = i * 4;
      if (entry + 3 >= raw.length) {
        break;
      }
      int sectorCount = raw[entry + 3] & 0xFF;
      if (sectorCount < 1) {
        continue;
      }
      int offset = ((raw[entry] & 0xFF) << 16) | ((raw[entry + 1] & 0xFF) << 8) | (raw[entry + 2] & 0xFF);
      int dataPos = offset * 4096;
      if (dataPos < 0 || dataPos + 4 > raw.length) {
        continue;
      }
      int declaredLen = ((raw[dataPos] & 0xFF) << 24) | ((raw[dataPos + 1] & 0xFF) << 16)
          | ((raw[dataPos + 2] & 0xFF) << 8) | (raw[dataPos + 3] & 0xFF);
      if (declaredLen < 1) {
        continue;
      }
      neededSectors += (declaredLen + 4 + 4095) / 4096; //+4: data-length prefix
    }
    reclaimableSectors = Math.max(0, fileSectors - neededSectors);
  }

  /** @return wasted sectors a defragmenting rewrite would reclaim; see {@link #analyzeLayout}. */
  public long getReclaimableSectors() {
    return reclaimableSectors;
  }

  /** @return an estimate of the bytes a defragmenting rewrite would reclaim. */
  public long getReclaimableBytes() {
    return reclaimableSectors * 4096;
  }

  /** @return whether a rewrite would reclaim wasted space (gaps, trailing padding, over-allocation). */
  public boolean hasReclaimableSpace() {
    return reclaimableSectors > 0;
  }

  /**
   * Sets the Chunk at a specific index, which must be in range {@code [0, CHUNK_COUNT)}.
   *
   * @param index The index of the Chunk.
   * @param chunk The Chunk to be set.
   * @throws IndexOutOfBoundsException If index is not in the range.
   */
  public void setChunk(int index, Chunk chunk) {
    checkIndex(index);
    if (chunks == null) {
      chunks = new Chunk[CHUNK_COUNT];
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
    if (index < 0 || index >= CHUNK_COUNT) {
      throw new IndexOutOfBoundsException();
    }
    return index;
  }

  @Override
  public Iterator<Chunk> iterator() {
    return Arrays.stream(chunks).iterator();
  }
}
