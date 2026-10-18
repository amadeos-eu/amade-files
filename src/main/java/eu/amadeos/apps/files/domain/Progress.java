package eu.amadeos.apps.files.domain;

import java.nio.file.Path;

/**
 * State of a running copy, move or delete.
 *
 * @param bytesDone  bytes copied (or files deleted) so far
 * @param bytesTotal total bytes (or files)
 * @param filesDone  files finished, including skipped ones
 * @param filesTotal files in the operation
 * @param current    file being processed, null when finished
 */
public record Progress(long bytesDone, long bytesTotal, int filesDone, int filesTotal, Path current) {

    /** 0 to 1. */
    public double fraction() {
        if (bytesTotal > 0) return Math.min(1.0, (double) bytesDone / bytesTotal);
        return filesTotal > 0 ? Math.min(1.0, (double) filesDone / filesTotal) : 0;
    }
}
