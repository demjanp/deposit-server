package deposit.utils;

import java.time.ZonedDateTime;

public class Versioning {
	
	public static void updateArchive() {

		// TODO: Implement

	}
	
	public static void updateDatabase(ZonedDateTime timeStamp) {

		// TODO: Implement

	}
	
	public static void updateObject(ZonedDateTime timeStamp, Boolean deleted, String uuid) {
		updateArchive();

		// TODO: Implement

		updateDatabase(timeStamp);

	}
	
	public static void updateClass(ZonedDateTime timeStamp, Boolean deleted, String class_name) {
		updateArchive();

		// TODO: Implement

		updateDatabase(timeStamp);
	}
	
	public static void updateDescriptor(ZonedDateTime timeStamp, Boolean deleted, String uuid, String descriptor_name) {
		updateArchive();

		// TODO: Implement

		updateDatabase(timeStamp);
	}
	
	public static void updateClassDescriptor(ZonedDateTime timeStamp, Boolean deleted, String class_name, String descriptor_name) {
		updateArchive();

		// TODO: Implement

		updateDatabase(timeStamp);
	}
	
	public static void updateMembership(ZonedDateTime timeStamp, Boolean deleted, String uuid, String class_name) {
		updateArchive();

		// TODO: Implement

		updateDatabase(timeStamp);

	}

	public static void updateRelationship(ZonedDateTime timeStamp, Boolean deleted, String uuid_source, String uuid_target) {
		updateArchive();

		// TODO: Implement

		updateDatabase(timeStamp);
	}
	
	public static void updateClassRelationship(ZonedDateTime timeStamp, Boolean deleted, String class_name_source, String class_name_target, String label) {
		updateArchive();

		// TODO: Implement

		updateDatabase(timeStamp);

	}
	
	public static void restore(ZonedDateTime timestamp) {

		// TODO: Implement

	}
	
	public static void undo() {

		// TODO: Implement

	}
	
	public static void redo() {

		// TODO: Implement

	}
	
	public static void isRestored() {

		// TODO: Implement

	}
	
	public static void canUndo() {

		// TODO: Implement

	}
}