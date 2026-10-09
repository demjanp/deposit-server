package deposit.common;

import org.apache.commons.vfs2.*;
import org.apache.commons.vfs2.FileNotFoundException;

import java.nio.file.Paths;
import java.io.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class FileSystem {
	
	private static final int TIMEOUT = 60; // seconds
	private static final int MAX_RETRIES = 5;
    private static final long INITIAL_WAIT_TIME_MS = 1000; // 1 second
	
	private static final Map<String, String> IMAGE_FORMATS = new HashMap<>();

	static {
		IMAGE_FORMATS.put("svg", "SVG");
		IMAGE_FORMATS.put("png", "PNG");
		IMAGE_FORMATS.put("jpg", "JPEG");
		IMAGE_FORMATS.put("jpeg", "JPEG");
		IMAGE_FORMATS.put("gif", "GIF");
		IMAGE_FORMATS.put("tif", "TIFF");
		IMAGE_FORMATS.put("tiff", "TIFF");
		IMAGE_FORMATS.put("bmp", "BMP");
	}

	private static FileSystem instance;
	
	private static FileObject transientStorage;
	private static FileObject permanentStorage;
	private static FileObject archiveStorage;

	private static final ReentrantLock lock = new ReentrantLock(true);

	public static synchronized FileSystem getInstance() throws FileSystemException {
		if (instance == null) {
			instance = new FileSystem();
		}
		return instance;
	}

	static {
		try {
			initializeFileSystem();
		} catch (FileSystemException e) {
			ExceptionHandler.throwException("Failed to initialize FileSystem", e);
		}
	}

	private static void initializeFileSystem() throws FileSystemException {
		
		FileSystemManager fsManager = VFS.getManager();

		String userHome = System.getProperty("user.home");

		String transientStoragePath = Paths.get(userHome, "Deposit", "transient_storage").toString();
		String permanentStoragePath = Paths.get(userHome, "Deposit", "permanent_storage").toString();
		String archiveStoragePath = Paths.get(userHome, "Deposit", "archive_storage").toString();

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

		clearTransient();
		// TODO : delete all orphaned files from the permanent and archive storage
	}

	// Stores a stream (base64 encoded string) as a file in the transient storage and returns the generated filename
	public static String storeFile(String base64Content, String originalFilename) {
		return executeWithLock(() -> {
			String generatedFilename = generateFilename(sanitizeFilename(originalFilename));
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

	public static String getImageFormat(String filename) {
		if (filename == null || !filename.contains(".")) {
			return "";
		}

		String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();

		return IMAGE_FORMATS.getOrDefault(extension, "");
	}

	// Creates a packaged database (GraphSON + specified files in a zipped directory) based on a GraphSON string and resource filenames and returns a base64 encoded string
	public static String packageData(String graphSONData, List resoures) {
		// TODO implement
		return null;
	}

	// Unpacks a packages database and returns the data as `{nodes: <Nodes>, relationships: <Relationships>, resources: [<local filename of the resource>, ...]}`
	public static HashMap<String, Object> unpackData(String filename) {
		// TODO implement
		return null;
	}

	// Retrieves a file from the transient storage and returns its contents as a base64 encoded string
	public static String readFromTransient(String filename) {
		return executeWithLock(() -> {
			try {
				FileObject file = transientStorage.resolveFile(filename);
				if (!file.exists()) {
					ExceptionHandler.throwException("File does not exist: " + file.getName().getURI());
				}
				try (InputStream is = file.getContent().getInputStream()) {
					byte[] data = is.readAllBytes();
					return Base64.getEncoder().encodeToString(data);
				}
			} catch (IOException e) {
				ExceptionHandler.throwException("Failed to retrieve file", e);
				return null;
			}
		});
	}

	// Retrieves a file from the permanent storage based on its filename (automatically derives the subdir)
	public static String readFromPermanent(String filename) {
		return executeWithLock(() -> {
			try {
				FileObject file = permanentStorage.resolveFile(generateHashBasedSubdir(filename) + "/" + filename);
				
				if (!file.exists()) {
					ExceptionHandler.throwException("File does not exist: " + file.getName().getURI());
				}
				
				try (InputStream is = file.getContent().getInputStream()) {
					byte[] data = is.readAllBytes();
					return Base64.getEncoder().encodeToString(data);
				}
			} catch (IOException e) {
				ExceptionHandler.throwException("Failed to stream file as base64", e);
				return null;
			}
		});
	}

	// Moves a file from the transient to the permanent storage (auto generates a subdir using hash-based sharding strategy)
	public static String moveToPermanent(String filename) {
		return executeWithLock(() -> {
			try {
				FileObject sourceFile = transientStorage.resolveFile(filename);
				FileObject destFile = permanentStorage.resolveFile(generateHashBasedSubdir(filename) + "/" + filename);

				if (!sourceFile.exists()) {
					ExceptionHandler.throwException("Source file does not exist: " + sourceFile.getName().getURI());
					return null;
				}

				if (destFile.exists()) {
					sourceFile.delete();
					return filename;
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

			} catch (FileSystemException e) {
				ExceptionHandler.throwException("Failed to move file to permanent storage", e);
				return null;
			}
			return filename;
		});
	}

	// Copies a file from the transient to the permanent storage
	public static String copyToPermanent(String filename) {
		return executeWithLock(() -> {
			try {
				FileObject sourceFile = transientStorage.resolveFile(filename);
				FileObject destFile = permanentStorage.resolveFile(generateHashBasedSubdir(filename) + "/" + filename);

				if (!sourceFile.exists()) {
					ExceptionHandler.throwException("Source file does not exist: " + sourceFile.getName().getURI());
					return null;
				}

				if (destFile.exists()) {
					return filename;
				}
				
				destFile.copyFrom(sourceFile, Selectors.SELECT_SELF);
				
			} catch (FileSystemException e) {
				ExceptionHandler.throwException("Failed to copy file to permanent storage", e);
				return null;
			}
			return filename;
		});
	}
	
	// Copies a file from the permanent to the transient storage
	public static String copyToTransient(String filename) {
		return executeWithLock(() -> {
			try {
				FileObject sourceFile = permanentStorage.resolveFile(generateHashBasedSubdir(filename) + "/" + filename);
				FileObject destFile = transientStorage.resolveFile(filename);

				if (!sourceFile.exists()) {
					ExceptionHandler.throwException("Source file does not exist: " + sourceFile.getName().getURI());
					return null;
				}

				if (destFile.exists()) {
					return filename;
				}
				
				destFile.copyFrom(sourceFile, Selectors.SELECT_SELF);
				
			} catch (FileSystemException e) {
				ExceptionHandler.throwException("Failed to copy file to transient storage", e);
				return null;
			}
			return filename;
		});
	}

	// Moves a file to the archive storage with a timestamp appended to the filename and returns the new filename
	public static String moveToArchive(String filename, ZonedDateTime timeStamp) {
		return executeWithLock(() -> {
			String newFilename = addTimeStamp(filename, timeStamp);
			try {
				FileObject sourceFile = permanentStorage.resolveFile(generateHashBasedSubdir(filename) + "/" + filename);
				FileObject destFile = archiveStorage.resolveFile(generateHashBasedSubdir(newFilename) + "/" + newFilename);

				if (destFile.exists()) {
					// File has already been moved to the archive by another transaction
					return newFilename;
				} else if (!sourceFile.exists()) {
					ExceptionHandler.throwException("Source file does not exist: " + sourceFile.getName().getURI());
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
			} catch (IOException e) {
				ExceptionHandler.throwException("Failed to move file to archive storage", e);
				return null;
			}
			return newFilename;
		});
	}

	// Restores a file from the archive to the permanent storage and returns the restored filename
	public static String restoreFromArchive(String archivedFilename) {
		return executeWithLock(() -> {
			String originalFilename = removeTimestampFromFilename(archivedFilename);
			try {
				FileObject sourceFile = archiveStorage.resolveFile(generateHashBasedSubdir(archivedFilename) + "/" + archivedFilename);
				FileObject destFile = permanentStorage.resolveFile(generateHashBasedSubdir(originalFilename) + "/" + originalFilename);
				
				if (destFile.exists()) {
					// File has already been restored
					return originalFilename;
				} else if (!sourceFile.exists()) {
					ExceptionHandler.throwException("Source file does not exist: " + sourceFile.getName().getURI());
				}
				destFile.copyFrom(sourceFile, Selectors.SELECT_SELF);
			} catch (IOException e) {
				ExceptionHandler.throwException("Failed to restore file from archive", e);
			}
			return originalFilename;
		});
	}
	
	// Deletes a file from the permanent storage
	public static String deleteFromPermanent(String filename) {
		return executeWithLock(() -> {
			try {
				FileObject file = permanentStorage.resolveFile(generateHashBasedSubdir(filename) + "/" + filename);
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
	
	// Deletes a file from the archive storage
	public static String deleteFromArchive(String filename) {
		return executeWithLock(() -> {
			try {
				FileObject file = archiveStorage.resolveFile(generateHashBasedSubdir(filename) + "/" + filename);
				if (file.exists()) {
					file.delete();
				}
				return null;
			} catch (FileSystemException e) {
				ExceptionHandler.throwException("Failed to delete file from archive storage", e);
				return null;
			}
		});
	}
	
	// Deletes all files from the transient storage
	public static String clearTransient() {
		return executeWithLock(() -> {
			try {
				for (FileObject file : transientStorage.getChildren()) {
					file.delete();
				}
				return null;
			} catch (FileSystemException e) {
				ExceptionHandler.throwException("Failed to clear transient storage", e);
				return null;
			}
		});
	}
	
	private static String generateFilename(String originalFilename) {
		String uuid = UUID.randomUUID().toString();
		String baseFilename = originalFilename.substring(0, originalFilename.lastIndexOf('.'));
		String extension = originalFilename.substring(originalFilename.lastIndexOf('.'));
		return baseFilename + "-" + uuid + extension;
	}
	
	private static String addTimeStamp(String originalFilename, ZonedDateTime timeStamp) {
		String baseFilename = originalFilename.substring(0, originalFilename.lastIndexOf('.'));
		String extension = originalFilename.substring(originalFilename.lastIndexOf('.'));
		String formattedTimestamp = timeStamp.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
		return baseFilename + "-" + formattedTimestamp + extension;
	}
	
	private static String generateHashBasedSubdir(String filename) {
		int hash = filename.hashCode();
		return Integer.toHexString(hash).substring(0, 2);  // Using first 2 hex characters
	}

	private static String removeTimestampFromFilename(String filename) {
		int lastDashIndex = filename.lastIndexOf('-');
		int extensionIndex = filename.lastIndexOf('.');
		if (lastDashIndex != -1 && extensionIndex != -1 && lastDashIndex < extensionIndex) {
			return filename.substring(0, lastDashIndex) + filename.substring(extensionIndex);
		}
		return filename;  // Return the original filename if the format is unexpected
	}
	
	private static void createDirectoryIfNotExists(FileObject dir) {
		try {
			if (!dir.exists()) {
				dir.createFolder();
			}
		} catch (FileSystemException e) {
			ExceptionHandler.throwException("Failed to create directory", e);
		}
	}

	private static String sanitizeFilename(String filename) {
		// Replace or remove unsafe characters
		return filename.replaceAll("[^a-zA-Z0-9.\\-]", "_");
	}
	
	private static <T> T __executeWithLock(Supplier<T> operation) {
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
	
	private static <T> T executeWithLock(Supplier<T> operation) {
		boolean acquired = false;
		T result = null;
		int attempt = 0;
		long waitTimeMillis = INITIAL_WAIT_TIME_MS;

		try {
			// Attempt to acquire the lock with a timeout
			acquired = lock.tryLock(TIMEOUT, TimeUnit.SECONDS);
			if (acquired) {
				while (attempt < MAX_RETRIES) {
					try {
						// Reset the exception flag before each operation
						ExceptionHandler.resetExceptionHandledFlag();
						
						// Try to execute the operation
						result = operation.get();
						break; // Exit loop if successful
					} catch (Exception e) {
						// If ExceptionHandler has handled the exception, do not retry
						if (!ExceptionHandler.hasExceptionBeenHandled()) {
							throw e; // Rethrow the exception if it's non-retryable
						}

						attempt++;
						if (attempt >= MAX_RETRIES) {
							ExceptionHandler.throwException("Operation failed after " + MAX_RETRIES + " attempts", e);
						} else {
							// Log and wait before retrying
							System.err.println("Attempt " + attempt + " failed. Retrying in " + waitTimeMillis + "ms...");
							try {
								Thread.sleep(waitTimeMillis);
							} catch (InterruptedException ie) {
								Thread.currentThread().interrupt();
								ExceptionHandler.throwException("Thread was interrupted during retry backoff", ie);
							}
							// Increase wait time for the next attempt
							waitTimeMillis *= 2;
						}
					}
				}
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
