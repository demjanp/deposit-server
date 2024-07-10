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
        String directoryPath = "src/main/java/deposit/api/";
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
            String description = null;

            while ((line = br.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("/**")) {
                    inProcedureComment = true;
                    commentBuilder.setLength(0);
                }

                if (inProcedureComment) {
                    commentBuilder.append(line).append("\n");
                }

                if (line.endsWith("*/")) {
                    inProcedureComment = false;
                }

                if (line.startsWith("@Procedure")) {
                    Pattern procedurePattern = Pattern.compile("name\\s*=\\s*\"([^\"]+)\"");
                    Matcher procedureMatcher = procedurePattern.matcher(line);
                    if (procedureMatcher.find()) {
                        procedureName = procedureMatcher.group(1);
                    }
                }

                if (line.startsWith("@Description")) {
                    Pattern descriptionPattern = Pattern.compile("@Description\\(\"([^\"]+)\"\\)");
                    Matcher descriptionMatcher = descriptionPattern.matcher(line);
                    if (descriptionMatcher.find()) {
                        description = descriptionMatcher.group(1);
                    }
                }

                if (!inProcedureComment && procedureName != null) {
                    processJavadocComment(commentBuilder.toString(), procedureName, description, writer);
                    procedureName = null; // Reset for next procedure
                    description = null; // Reset for next procedure
                }
            }
        }
    }

    private static void processJavadocComment(String comment, String procedureName, String description, BufferedWriter writer) throws IOException {
        Pattern paramPattern = Pattern.compile("@param ([^ ]+) (.+)");
        Pattern throwsPattern = Pattern.compile("@throws ([^ ]+) (.+)");
        Pattern javadocDescriptionPattern = Pattern.compile("/\\*\\*\\s*(.*?)\\s*(?=@param|@throws|\\*/)", Pattern.DOTALL);

        Matcher javadocDescriptionMatcher = javadocDescriptionPattern.matcher(comment);
        String javadocDescription = "";

        if (javadocDescriptionMatcher.find()) {
            javadocDescription = javadocDescriptionMatcher.group(1).replaceAll("\\s*\\*\\s*", " ").trim();
            javadocDescription = javadocDescription.replaceAll("\\n+", "\\n"); // Ensure single line breaks
        }

        if (procedureName != null) {
            writer.write("## " + procedureName + "\n\n");
            writer.write(javadocDescription + "\n\n");

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
            if (description != null && !description.isEmpty()) {
                writer.write("```cypher\n" + description + "\n```\n\n");
            } else {
                writer.write("```cypher\nCALL " + procedureName + "(parameters)\n```\n\n");
            }
        }
    }
}
