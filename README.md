## AutoPruner

This is a simple utility for automatically deleting empty chunks and chunk sections from minecraft minigame maps. It works with worlds from 1.8 up through the latest version (1.21), detecting each chunk's format automatically.

## Usage

- `java -jar AutoPruner-1.0.jar -d [path to directory with maps]`
  - or `java -jar AutoPruner-1.0.jar -f [path to .mca file]`
  - add `-t [threads]` to prune a directory using multiple threads
  - add `-n` (`--dry-run`) to preview what would be removed without modifying any files
- Run with no arguments to open the folder-picker GUI.

A chunk is removed only when it has no blocks, block/tile entities, entities, or non-default biomes; a region file is deleted once all of its chunks are gone. Surviving chunks are written back unchanged, so version- and server-specific data is preserved. For 1.18+ worlds the separate `entities/` region is checked so chunks with entity data are kept.

A region file that has no empty chunks but still wastes space (gaps left between chunks, trailing padding, or over-allocated slots) is defragmented by rewriting it back-to-back, reclaiming that space without touching chunk content. Files that are already tightly packed are left untouched, so repeated runs are idempotent.

After a directory run, a summary of files skipped/compacted/pruned/deleted (broken down by world version) is printed once enough files have changed.

## Notes

- Adapted NBT handling code from https://github.com/Querz/NBT and modified it to work for 1.8 through 1.21 worlds.
