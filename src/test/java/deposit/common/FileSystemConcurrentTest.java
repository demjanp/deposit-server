package deposit.common;

import org.apache.commons.vfs2.FileSystemException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.List;
import java.util.ArrayList;

import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileSystemConcurrentTest {

	private FileSystem fileSystem;

	@BeforeEach
	void setUp(@TempDir Path tempDir) throws Exception {
		System.setProperty("user.home", tempDir.toString());
		fileSystem = FileSystem.getInstance();
	}

	@Test
	void testConcurrentReadFromPermanent() throws ExecutionException, InterruptedException {
		String filename = createTestFile();

		List<CompletableFuture<Void>> tasks = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			tasks.add(CompletableFuture.runAsync(() -> assertDoesNotThrow(() -> fileSystem.readFromPermanent(filename))));
		}

		CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).get();
	}

	@Test
	void testConcurrentMoveToArchive() throws ExecutionException, InterruptedException {
		String filename = createTestFile();
		ZonedDateTime timestamp = ZonedDateTime.now();

		List<CompletableFuture<Void>> tasks = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			tasks.add(CompletableFuture.runAsync(() -> assertDoesNotThrow(() -> fileSystem.moveToArchive(filename, timestamp))));
		}

		CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).get();
	}

	@Test
	void testConcurrentRestoreFromArchive() throws ExecutionException, InterruptedException {
		String filename = createAndArchiveTestFile();

		List<CompletableFuture<Void>> tasks = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			tasks.add(CompletableFuture.runAsync(() -> assertDoesNotThrow(() -> fileSystem.restoreFromArchive(filename))));
		}

		CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).get();
	}

	@Test
	void testConcurrentDeleteFromPermanent() throws ExecutionException, InterruptedException {
		String filename = createTestFile();

		List<CompletableFuture<Void>> tasks = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			tasks.add(CompletableFuture.runAsync(() -> assertDoesNotThrow(() -> fileSystem.deleteFromPermanent(filename))));
		}

		CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).get();
	}

	@Test
	void testConcurrentDeleteFromArchive() throws ExecutionException, InterruptedException {
		String filename = createAndArchiveTestFile();

		List<CompletableFuture<Void>> tasks = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			tasks.add(CompletableFuture.runAsync(() -> assertDoesNotThrow(() -> fileSystem.deleteFromArchive(filename))));
		}

		CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).get();
	}

	@Test
	void testConcurrentReadAndMoveToArchive() throws ExecutionException, InterruptedException {
		String filename = createTestFile();
		ZonedDateTime timestamp = ZonedDateTime.now();

		List<CompletableFuture<Void>> tasks = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			tasks.add(CompletableFuture.runAsync(() -> {
				try {
					fileSystem.readFromPermanent(filename);
				} catch (RuntimeException e) {
					// If an exception occurs, it should be related to the file being moved.
					assertTrue("Unexpected exception message: " + e.getMessage(),
							e.getMessage().contains("File does not exist:"));
				}
			}));

			tasks.add(CompletableFuture.runAsync(() -> {
				// Ensure moveToArchive does not throw an error
				assertDoesNotThrow(() -> fileSystem.moveToArchive(filename, timestamp));
			}));
		}

		// Wait for all tasks to complete
		CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).get();
	}

	@Test
	void testConcurrentReadAndDeleteFromPermanent() throws ExecutionException, InterruptedException {
		String filename = createTestFile();

		List<CompletableFuture<Void>> tasks = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			// Reading the file
			tasks.add(CompletableFuture.runAsync(() -> {
				try {
					fileSystem.readFromPermanent(filename);
				} catch (RuntimeException e) {
					// Expecting an exception when the file has been deleted by another thread
					assertTrue("Unexpected exception message: " + e.getMessage(),
							e.getMessage().startsWith("File does not exist:"));
				}
			}));

			// Deleting the file
			tasks.add(CompletableFuture.runAsync(() -> {
				// Ensure deleteFromPermanent does not throw an error
				assertDoesNotThrow(() -> fileSystem.deleteFromPermanent(filename));
			}));
		}

		// Wait for all tasks to complete
		CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).get();
	}

	@Test
	void testConcurrentMoveToArchiveAndDeleteFromPermanent() throws ExecutionException, InterruptedException {
		String filename = createTestFile();
		ZonedDateTime timestamp = ZonedDateTime.now();

		List<CompletableFuture<Void>> tasks = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			tasks.add(CompletableFuture.runAsync(() -> assertDoesNotThrow(() -> fileSystem.moveToArchive(filename, timestamp))));
			tasks.add(CompletableFuture.runAsync(() -> assertDoesNotThrow(() -> fileSystem.deleteFromPermanent(filename))));
		}

		CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).get();
	}

	@Test
	void testConcurrentRestoreFromArchiveAndDeleteFromArchive() throws ExecutionException, InterruptedException {
		String filename = createAndArchiveTestFile();

		List<CompletableFuture<Void>> tasks = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			tasks.add(CompletableFuture.runAsync(() -> assertDoesNotThrow(() -> fileSystem.restoreFromArchive(filename))));
			tasks.add(CompletableFuture.runAsync(() -> assertDoesNotThrow(() -> fileSystem.deleteFromArchive(filename))));
		}

		CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).get();
	}

	@Test
	void testTimeoutMechanism() throws ExecutionException, InterruptedException, FileSystemException {
		String originalFilename = "testFile.txt";
		String content = "This is a test file.";
		String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

		FileSystem fileSystem2 = FileSystem.getInstance();
		String generatedFilename = fileSystem2.storeFile(base64Content, originalFilename);
		fileSystem2.setTimeout(1);

		// Task 1 will hold the lock for 10 seconds, which is longer than the 5-second timeout
		CompletableFuture<Void> longRunningTask = CompletableFuture.runAsync(() -> {
			fileSystem2.testTimeout(3000); // Acquires the lock
		});

		// Give the first task time to acquire the lock
		Thread.sleep(500);

		// Task 2 will attempt to acquire the lock, but should time out after 5 seconds
		CompletableFuture<Void> timeoutTask = CompletableFuture.runAsync(() -> {
			RuntimeException exception = assertThrows(RuntimeException.class, () -> fileSystem2.readFromPermanent(generatedFilename));
			assertTrue(exception.getMessage().contains("Timeout: Could not acquire lock within 1 seconds."));
		});

		// Wait for both tasks to complete
		CompletableFuture.allOf(longRunningTask, timeoutTask).get();
	}

	private String createTestFile() {
		String originalFilename = "testFile.txt";
		String content = "This is a test file.";
		String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

		String generatedFilename = fileSystem.storeFile(base64Content, originalFilename);
		fileSystem.moveToPermanent(generatedFilename);

		return generatedFilename;
	}

	private String createAndArchiveTestFile() {
		String generatedFilename = createTestFile();
		generatedFilename = fileSystem.moveToArchive(generatedFilename, ZonedDateTime.now());
		return generatedFilename;
	}
}
