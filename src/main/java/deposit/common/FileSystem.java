package deposit.common;

import org.apache.commons.vfs2.*;
import java.nio.file.Paths;
import java.io.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class FileSystem {

	private static FileObject transientStorage;
	private static FileObject permanentStorage;
	private static FileObject archiveStorage;
	private static final ConcurrentMap<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();

	private static volatile FileSystem instance;

	public static FileSystem getInstance() {
		if (instance == null) {
			synchronized (FileSystem.class) {
				if (instance == null) {
					try {
						instance = new FileSystem();
					} catch (FileSystemException e) {
						ExceptionHandler.throwException("Failed to initialize FileSystem", e);
					}
				}
			}
		}
		return instance;
	}

	private FileSystem() throws FileSystemException {
		FileSystemManager fsManager = VFS.getManager();

		String userHome = System.getProperty("user.home");

		String transientStoragePath = Paths.get(userHome, "deposit_transient_storage").toString();
		String permanentStoragePath = Paths.get(userHome, "deposit_permanent_storage").toString();
		String archiveStoragePath = Paths.get(userHome, "deposit_archive_storage").toString();

		try {
			transientStorage = fsManager.resolveFile(transientStoragePath);
			permanentStorage = fsManager.resolveFile(permanentStoragePath);
			archiveStorage = fsManager.resolveFile(archiveStoragePath);

			createDirectoryIfNotExists(transientStorage);
			createDirectoryIfNotExists(permanentStorage);
			createDirectoryIfNotExists(archiveStorage);
		} catch (FileSystemException e) {
			ExceptionHandler.throwException("Failed to initialize storage directories", e);
		}
	}

	private void executeWithFileLock(String filename, Runnable operation) {
		ReentrantLock lock = fileLocks.computeIfAbsent(filename, k -> new ReentrantLock());
		lock.lock();
		try {
			operation.run();
		} finally {
			lock.unlock();
			fileLocks.remove(filename); // Clean up to avoid memory leaks
		}
	}

	// Stores a stream (base64 encoded string) as a file in the transient storage and returns the generated filename
	public String storeFile(String base64Content, String originalFilename) {
		return executeWithFileLockAndReturn(originalFilename, () -> {
			String sanitizedFilename = sanitizeFilename(originalFilename);
			String generatedFilename = generateFilename(sanitizedFilename, null);
			try {
				byte[] data = Base64.getDecoder().decode(base64Content);
				FileObject file = transientStorage.resolveFile(generatedFilename);
				try (OutputStream os = file.getContent().getOutputStream()) {
					os.write(data);
				}
			} catch (IOException e) {
				ExceptionHandler.throwException("Failed to store stream", e);
			}
			return generatedFilename;
		});
	}

	// Creates a packaged database (GraphSON + specified files in a zipped directory) based on a GraphSON string and resource filenames and returns a base64 encoded string
	public String packageData(String graphSONData, List resoures) {
		// TODO implement
		return null;
	}

	// Unpacks a packages database and returns the data as `{nodes: <Nodes>, relationships: <Relationships>, resources: [<local filename of the resource>, ...]}`
	public HashMap<String, Object> unpackData(String filename) {
		// TODO implement
		return null;
	}

	// Retrieves a file from the transient storage and returns its contents as a base64 encoded string
	public String readFromTransient(String filename) {
		return executeWithFileLockAndReturn(filename, () -> {
			try {
				FileObject file = transientStorage.resolveFile(filename);
				try (InputStream is = file.getContent().getInputStream()) {
					byte[] data = is.readAllBytes();
					return Base64.getEncoder().encodeToString(data);
				}
			} catch (IOException e) {
				ExceptionHandler.throwException("Failed to retrieve file", e);
				return null;  // This will never be reached but is required to satisfy the compiler
			}
		});
	}

	// Retrieves a file from the permanent storage based on its filename (automatically derives the subdir)
	public String readFromPermanent(String filename) {
		return executeWithFileLockAndReturn(filename, () -> {
			try {
				FileObject file = permanentStorage.resolveFile(generateHashBasedSubdir(filename) + "/" + filename);
				try (InputStream is = file.getContent().getInputStream()) {
					byte[] data = is.readAllBytes();
					return Base64.getEncoder().encodeToString(data);
				}
			} catch (IOException e) {
				ExceptionHandler.throwException("Failed to stream file as base64", e);
				return null;  // This will never be reached but is required to satisfy the compiler
			}
		});
	}

	// Moves a file from the transient to the permanent storage (auto generates a subdir using hash-based sharding strategy)
	public void moveToPermanent(String filename) {
		executeWithFileLock(filename, () -> {
			try {
				String subdir = generateHashBasedSubdir(filename);
				FileObject sourceFile = transientStorage.resolveFile(filename);
				FileObject destFile = permanentStorage.resolveFile(subdir + "/" + filename);

				if (!sourceFile.exists()) {
					throw new FileSystemException("Source file does not exist: " + sourceFile.getName().getURI());
				}

				if (destFile.exists()) {
					throw new FileSystemException("Destination file already exists: " + destFile.getName().getURI());
				}

				try {
					sourceFile.moveTo(destFile);
				} catch (FileSystemException e) {
					System.err.println("Move failed, attempting copy and delete as fallback: " + e.getMessage());
					try {
						destFile.copyFrom(sourceFile, Selectors.SELECT_SELF);
						sourceFile.delete();
					} catch (Exception fallbackException) {
						ExceptionHandler.throwException("Failed to copy and delete file as fallback after move failure", fallbackException);
					}
				}

			} catch (FileSystemException e) {
				ExceptionHandler.throwException("Failed to move file to permanent storage", e);
			}
		});
	}

	// Moves a file to the archive storage with a timestamp appended to the filename and returns the new filename
	public String moveToArchive(String filename, ZonedDateTime timeStamp) {
		return executeWithFileLockAndReturn(filename, () -> {
			String newFilename = generateFilename(filename, timeStamp);
			try {
				FileObject sourceFile = permanentStorage.resolveFile(generateHashBasedSubdir(filename) + "/" + filename);
				FileObject destFile = archiveStorage.resolveFile(newFilename);

				if (!sourceFile.exists()) {
					throw new FileSystemException("Source file does not exist: " + sourceFile.getName().getURI());
				}

				if (destFile.exists()) {
					throw new FileSystemException("Destination file already exists: " + destFile.getName().getURI());
				}

				try {
					sourceFile.moveTo(destFile);
				} catch (FileSystemException e) {
					System.err.println("Move failed, attempting copy and delete as fallback: " + e.getMessage());
					try {
						destFile.copyFrom(sourceFile, Selectors.SELECT_SELF);
						sourceFile.delete();
					} catch (Exception fallbackException) {
						ExceptionHandler.throwException("Failed to copy and delete file as fallback after move failure", fallbackException);
					}
				}
			} catch (IOException e) {
				ExceptionHandler.throwException("Failed to move file to archive storage", e);
			}
			return newFilename;
		});
	}

	// Restores a file from the archive to the permanent storage and returns the restored filename
	public String restoreFromArchive(String archivedFilename) {
		return executeWithFileLockAndReturn(archivedFilename, () -> {
			String originalFilename = removeTimestampFromFilename(archivedFilename);
			try {
				String subdir = generateHashBasedSubdir(originalFilename);
				FileObject sourceFile = archiveStorage.resolveFile(archivedFilename);
				FileObject destFile = permanentStorage.resolveFile(subdir + "/" + originalFilename);
				if (!sourceFile.exists()) {
					throw new FileSystemException("Source file does not exist: " + sourceFile.getName().getURI());
				}

				if (destFile.exists()) {
					throw new FileSystemException("Destination file already exists: " + destFile.getName().getURI());
				}
				destFile.copyFrom(sourceFile, Selectors.SELECT_SELF);
			} catch (IOException e) {
				ExceptionHandler.throwException("Failed to restore file from archive", e);
			}
			return originalFilename;
		});
	}

	public void deleteFromPermanent(String filename) {
		executeWithFileLock(filename, () -> {
			try {
				String subdir = generateHashBasedSubdir(filename);
				FileObject file = permanentStorage.resolveFile(subdir + "/" + filename);
				if (file.exists()) {
					file.delete();
				} else {
					ExceptionHandler.throwException("File not found in permanent storage: " + filename);
				}
			} catch (FileSystemException e) {
				ExceptionHandler.throwException("Failed to delete file from permanent storage", e);
			}
		});
	}

	public void deleteFromArchive(String filename) {
		executeWithFileLock(filename, () -> {
			try {
				FileObject file = archiveStorage.resolveFile(filename);
				if (file.exists()) {
					file.delete();
				} else {
					ExceptionHandler.throwException("File not found in archive storage: " + filename);
				}
			} catch (FileSystemException e) {
				ExceptionHandler.throwException("Failed to delete file from archive storage", e);
			}
		});
	}

	public void clearTransient() {
		executeWithFileLock("clearTransient", () -> {
			try {
				for (FileObject file : transientStorage.getChildren()) {
					file.delete();
				}
			} catch (FileSystemException e) {
				ExceptionHandler.throwException("Failed to clear transient storage", e);
			}
		});
	}

	// Private method to generate a new filename based on the original one
	private String generateFilename(String originalFilename, ZonedDateTime timeStamp) {
		String uuid = UUID.randomUUID().toString();
		String baseFilename = originalFilename.substring(0, originalFilename.lastIndexOf('.'));
		String extension = originalFilename.substring(originalFilename.lastIndexOf('.'));

		if (timeStamp != null) {
			String formattedTimestamp = timeStamp.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
			return baseFilename + "-" + uuid + "-" + formattedTimestamp + extension;
		}
		return baseFilename + "-" + uuid + extension;
	}

	// Helper to generate hash-based subdirectory
	private String generateHashBasedSubdir(String filename) {
		int hash = filename.hashCode();
		return Integer.toHexString(hash).substring(0, 2);  // Using first 2 hex characters
	}

	// Helper method to remove the timestamp from the filename
	private String removeTimestampFromFilename(String filename) {
		int lastDashIndex = filename.lastIndexOf('-');
		int extensionIndex = filename.lastIndexOf('.');
		if (lastDashIndex != -1 && extensionIndex != -1 && lastDashIndex < extensionIndex) {
			return filename.substring(0, lastDashIndex) + filename.substring(extensionIndex);
		}
		return filename;  // Return the original filename if the format is unexpected
	}

	// Helper to create directories if they don’t exist
	private void createDirectoryIfNotExists(FileObject dir) {
		try {
			if (!dir.exists()) {
				dir.createFolder();
			}
		} catch (FileSystemException e) {
			ExceptionHandler.throwException("Failed to create directory", e);
		}
	}

	private String sanitizeFilename(String filename) {
		// Replace or remove unsafe characters
		return filename.replaceAll("[^a-zA-Z0-9.\\-]", "_");
	}

	// Helper method for operations that return a value
	private <T> T executeWithFileLockAndReturn(String filename, Supplier<T> operation) {
		ReentrantLock lock = fileLocks.computeIfAbsent(filename, k -> new ReentrantLock());
		lock.lock();
		try {
			return operation.get();
		} finally {
			lock.unlock();
			fileLocks.remove(filename); // Clean up to avoid memory leaks
		}
	}
}
