package deposit.common;

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

class FileSystemConcurrentTest {

	private FileSystem fileSystem;

	@BeforeEach
	void setUp() throws Exception {
		String homeDir = System.getProperty("java.io.tmpdir").toString() + "Deposit_Testing";
		System.setProperty("user.home", homeDir);
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
