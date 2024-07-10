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
        String outputFilePath = "doc/apoc_procedures_documentation.html";
        generateDocumentation(directoryPath, outputFilePath);
    }

    public static void generateDocumentation(String directoryPath, String outputFilePath) {
        File directory = new File(directoryPath);
        if (directory.exists() && directory.isDirectory()) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath))) {
                writer.write("<html><head><title>APOC Procedures Documentation</title>");
                writer.write("<link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"></head><body>\n");
                writer.write("<h1>APOC Procedures Documentation</h1>\n");
                processDirectory(directory, writer);
                writer.write("</body></html>");
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
        Pattern descriptionPattern = Pattern.compile("/\\*\\*\\s*(.*?)\\s*\\*/", Pattern.DOTALL);

        Matcher descriptionMatcher = descriptionPattern.matcher(comment);
        String description = "";
        if (descriptionMatcher.find()) {
            description = descriptionMatcher.group(1).replaceAll("\\s*\\*\\s*", " ").trim();
            description = description.replaceAll("\\n+", "\n"); // Ensure single line breaks
            description = description.replaceAll("\\n", "<br>\n"); // Convert line breaks to <br>
        }

        if (procedureName != null) {
            writer.write("<div class='procedure'>\n");
            writer.write("<h2>" + procedureName + "</h2>\n");
            writer.write("<p>" + description + "</p>\n");

            writer.write("<div class='parameters'><h3>Parameters:</h3><ul>\n");
            Matcher paramMatcher = paramPattern.matcher(comment);
            while (paramMatcher.find()) {
                writer.write("<li><strong>" + paramMatcher.group(1) + "</strong>: " + paramMatcher.group(2) + "</li>\n");
            }
            writer.write("</ul></div>\n");

            writer.write("<div class='throws'><h3>Throws:</h3><ul>\n");
            Matcher throwsMatcher = throwsPattern.matcher(comment);
            while (throwsMatcher.find()) {
                writer.write("<li><strong>" + throwsMatcher.group(1) + "</strong>: " + throwsMatcher.group(2) + "</li>\n");
            }
            writer.write("</ul></div>\n");

            writer.write("<div class='usage'><h3>Usage:</h3>\n");
            writer.write("<pre>CALL " + procedureName + "(parameters)</pre>\n");
            writer.write("</div>\n");

            writer.write("</div>\n");
        }
    }
}
