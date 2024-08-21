package deposit.common;

import org.apache.commons.vfs2.*;
import java.nio.file.Paths;
import java.io.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class FSReduced {
	
	private static int TIMEOUT = 60; // seconds
	
	private static FSReduced instance;
	
	private static FileObject transientStorage;
	private static FileObject permanentStorage;
	private static FileObject archiveStorage;
	
	private final ReentrantLock lock = new ReentrantLock(true);

	public static synchronized FSReduced getInstance() throws FileSystemException {
		if (instance == null) {
			instance = new FSReduced();
		}
		return instance;
	}

	private FSReduced() throws FileSystemException {
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

	// Stores a stream (base64 encoded string) as a file in the transient storage and returns the generated filename
	public String storeFile(String base64Content, String originalFilename) {
		return executeWithLock(() -> {
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

	// Retrieves a file from the permanent storage based on its filename (automatically derives the subdir)
	public String readFromPermanent(String filename) {
		return executeWithLock(() -> {
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
	public String moveToPermanent(String filename) {
		return executeWithLock(() -> {
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
					try {
						destFile.copyFrom(sourceFile, Selectors.SELECT_SELF);
						sourceFile.delete();
					} catch (Exception fallbackException) {
						ExceptionHandler.throwException("Failed to copy and delete file as fallback after move failure", fallbackException);
					}
				}
				return null;

			} catch (FileSystemException e) {
				ExceptionHandler.throwException("Failed to move file to permanent storage", e);
				return null;
			}
		});
	}

	// Deletes a file from the permanent storage
	public String deleteFromPermanent(String filename) {
		return executeWithLock(() -> {
			try {
				String subdir = generateHashBasedSubdir(filename);
				FileObject file = permanentStorage.resolveFile(subdir + "/" + filename);
				if (file.exists()) {
					file.delete();
				}
				return null;
			} catch (FileSystemException e) {
				ExceptionHandler.throwException("Failed to delete file from permanent storage", e);
				return null;
			}
		});
	}
	
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
	
	private String generateHashBasedSubdir(String filename) {
		int hash = filename.hashCode();
		return Integer.toHexString(hash).substring(0, 2);  // Using first 2 hex characters
	}

	private String removeTimestampFromFilename(String filename) {
		int lastDashIndex = filename.lastIndexOf('-');
		int extensionIndex = filename.lastIndexOf('.');
		if (lastDashIndex != -1 && extensionIndex != -1 && lastDashIndex < extensionIndex) {
			return filename.substring(0, lastDashIndex) + filename.substring(extensionIndex);
		}
		return filename;  // Return the original filename if the format is unexpected
	}
	
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
	
	private <T> T executeWithLock(Supplier<T> operation) {
		boolean acquired = false;
		T result = null;
        try {
            acquired = lock.tryLock(TIMEOUT, TimeUnit.SECONDS);
            if (acquired) {
                result = operation.get();
            } else {
                ExceptionHandler.throwException("Timeout: Could not acquire lock within " + TIMEOUT + " seconds.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ExceptionHandler.throwException("Thread was interrupted while waiting to acquire lock", e);
        } finally {
            if (acquired) {
                lock.unlock();
            }
        }
		return result;
	}
}
