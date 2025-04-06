package UdpChatServer.model;

/**
 * Represents metadata about a file stored on the server. Used for managing file
 * transfers and downloads.
 */
public class FileMetaData {
    private final String filename;
    private final long fileSize;
    private final String serverPath; // Path within server_storage

    public FileMetaData(String filename, long fileSize, String serverPath) {
        this.filename = filename;
        this.fileSize = fileSize;
        this.serverPath = serverPath;
    }

    public String getFilename() {
        return filename;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getServerPath() {
        return serverPath;
    }

    @Override
    public String toString() {
        return "FileMetaData{"
                + "filename='" + filename + '\''
                + ", fileSize=" + fileSize
                + ", serverPath='" + serverPath + '\''
                + '}';
    }
}
