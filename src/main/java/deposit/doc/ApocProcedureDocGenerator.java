package deposit.doc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ApocProcedureDocGenerator {

    public static void main(String[] args) {
        String directoryPath = "src/main/java/deposit/";
        String outputFilePath = "doc/apoc_procedures.md";
        generateDocumentation(directoryPath, outputFilePath);
    }

    public static void generateDocumentation(String directoryPath, String outputFilePath) {
        File directory = new File(directoryPath);
        if (directory.exists() && directory.isDirectory()) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath))) {
                writer.write("# Deposit server APOC procedures\n\n");
                processDirectory(directory, writer);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            System.err.println("The specified directory does not exist: " + directoryPath);
        }
    }

    private static void processDirectory(File directory, BufferedWriter writer) throws IOException {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    processDirectory(file, writer);
                } else if (file.getName().endsWith(".java")) {
                    processFile(file, writer);
                }
            }
        }
    }

    private static void processFile(File file, BufferedWriter writer) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            boolean inProcedureComment = false;
            StringBuilder commentBuilder = new StringBuilder();
            String procedureName = null;
            String procedureAnnotationPattern = "@Procedure\\(name = \"([^\"]+)\"";

            while ((line = br.readLine()) != null) {
                if (line.contains("/**")) {
                    inProcedureComment = true;
                    commentBuilder.setLength(0); // Clear the builder
                }

                if (inProcedureComment) {
                    commentBuilder.append(line).append("\n");
                    if (line.contains("*/")) {
                        inProcedureComment = false;
                    }
                } else if (line.contains("@Procedure(")) {
                    // Capture the procedure name
                    Pattern pattern = Pattern.compile(procedureAnnotationPattern);
                    Matcher matcher = pattern.matcher(line);
                    if (matcher.find()) {
                        procedureName = matcher.group(1);
                    }
                } else if (procedureName != null) {
                    processJavadocComment(commentBuilder.toString(), procedureName, writer);
                    procedureName = null; // Reset for next procedure
                }
            }
        }
    }

    private static void processJavadocComment(String comment, String procedureName, BufferedWriter writer) throws IOException {
        Pattern paramPattern = Pattern.compile("@param ([^ ]+) (.+)");
        Pattern throwsPattern = Pattern.compile("@throws ([^ ]+) (.+)");
        Pattern descriptionPattern = Pattern.compile("/\\*\\*\\s*(.*?)\\s*(?=@param|@throws|\\*/)", Pattern.DOTALL);

        Matcher descriptionMatcher = descriptionPattern.matcher(comment);
        String description = "";
        if (descriptionMatcher.find()) {
            description = descriptionMatcher.group(1).replaceAll("\\s*\\*\\s*", " ").trim();
            description = description.replaceAll("\\n+", "\n"); // Ensure single line breaks
        }

        if (procedureName != null) {
            writer.write("## " + procedureName + "\n\n");
            writer.write(description + "\n\n");

            Matcher paramMatcher = paramPattern.matcher(comment);
            if (paramMatcher.find()) {
                writer.write("### Parameters:\n\n");
                do {
                    writer.write("- **" + paramMatcher.group(1) + "**: " + paramMatcher.group(2) + "\n");
                } while (paramMatcher.find());
                writer.write("\n");
            }

            Matcher throwsMatcher = throwsPattern.matcher(comment);
            if (throwsMatcher.find()) {
                writer.write("### Throws:\n\n");
                do {
                    writer.write("- **" + throwsMatcher.group(1) + "**: " + throwsMatcher.group(2) + "\n");
                } while (throwsMatcher.find());
                writer.write("\n");
            }

            writer.write("### Usage:\n\n");
            writer.write("```cypher\nCALL " + procedureName + "(parameters)\n```\n\n");
        }
    }
}
