/*******************************************************************************
 * Copyright (c) 2005-2021 Maxprograms.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-v10.html
 *
 * Contributors:
 *     Maxprograms - initial API and implementation
 *******************************************************************************/ 
package com.maxprograms.tmxvalidation;

import java.io.*;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

import com.maxprograms.xml.CustomErrorHandler;
import com.maxprograms.xml.SAXBuilder;

public class TMXValidator {
	private static final Logger LOGGER = System.getLogger(TMXValidator.class.getName());
	
	private SAXBuilder builder;
	private TMXValidatingHandler handler;
	private TMXResolver resolver;
	
	public TMXValidator() {
		handler = new TMXValidatingHandler();
		resolver = new TMXResolver();
		builder = new SAXBuilder();
		builder.setValidating(true);
		builder.setContentHandler(handler);
		builder.setEntityResolver(resolver);
		builder.setErrorHandler(new CustomErrorHandler());
	}

	public void validate(File file) throws IOException, SAXException, ParserConfigurationException {
		try {
			builder.build(file);
		} catch (SAXException sax) {
			if (sax.getMessage().equals(TMXValidatingHandler.RELOAD)) {
				// TMX DTD was not declared
				String version = handler.getVersion();
				File copy = File.createTempFile("copy", ".tmx");
				copy.deleteOnExit();
				copyFile(file, copy, version);
				builder.build(copy);
			} else {
				throw sax;
			}
		}
	}

	private static void copyFile(File file, File copy, String version) throws IOException, SAXException, ParserConfigurationException {
		String systemID = "tmx14.dtd";
		if (version.equals("1.3")) {
			systemID = "tmx13.dtd";
		} else if (version.equals("1.2")) {
			systemID = "tmx12.dtd";
		} else if (version.equals("1.1")) {
			systemID = "tmx11.dtd";
		}
		try (FileOutputStream out = new FileOutputStream(copy)) {
			writeString(out, "<?xml version=\"1.0\" ?>\n");
			writeString(out, "<!DOCTYPE tmx SYSTEM \"" + systemID + "\">\n");
			SAXBuilder copyBuilder = new SAXBuilder();
			TMXCopyHandler copyHandler = new TMXCopyHandler(out);
			copyBuilder.setContentHandler(copyHandler);
			copyBuilder.build(file);
		}
	}

	private static void writeString(FileOutputStream out, String string) throws IOException {
		out.write(string.getBytes(StandardCharsets.UTF_8));
	}

	private static void validateSingleFile(TMXValidator validator, File tmxFile) {
		try {
			validator.validate(tmxFile);
			LOGGER.log(Level.INFO, String.format("'%s' is valid TMX", tmxFile.toString()));
		} catch (IOException | SAXException | ParserConfigurationException e) {
			LOGGER.log(Level.ERROR, e.getMessage());
		}
	}
	
	public static void main(String[] args) {
		String[] commandLine = fixPath(args);
		String tmx = "";
		for (int i = 0; i < commandLine.length; i++) {
			String arg = commandLine[i];
			if (arg.equals("-version")) {
				LOGGER.log(Level.INFO, () -> "Version: " + Constants.VERSION + " Build: " + Constants.BUILD);
				return;
			}
			if (arg.equals("-help")) {
				help();
				return;
			}
			if (System.getProperty("file.separator").equals("/")) {
				if (arg.equals("-tmx") && (i + 1) < commandLine.length) {
					tmx = commandLine[i + 1];
				}
			}
			else {
//				TODO: on Windows, take a path a file containing list of TMX files instead, e.g. tmx.lst
				if (arg.equals("-tmx")) {
					Properties prop = new Properties();

					try (InputStream input = new FileInputStream("tmx.properties")) {
						prop.load(new InputStreamReader(input, StandardCharsets.UTF_8));
					}
					catch (IOException e) {
						LOGGER.log(Level.ERROR, e.getMessage());
						return;
					}

					tmx = prop.getProperty("tmx", "");
				}
			}
		}
		if (tmx.isEmpty()) {
			help();
			return;
		}

		TMXValidator validator = new TMXValidator();
		Path tmxPath = Paths.get(tmx);

		if (Files.isRegularFile(tmxPath)) {
			validateSingleFile(validator, tmxPath.toFile());
		}
		else if (Files.isDirectory(tmxPath)) {
			try (Stream<Path> stream = Files.list(tmxPath)) {
				stream.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".tmx"))
						.forEach(path -> validateSingleFile(validator, path.toFile()));
			} catch (IOException e) {
				LOGGER.log(Level.ERROR, e.getMessage());
			}
		}
	}
	
	private static void help() {
		String usageIntro = "Usage:\n\n   java -cp \"jars/*\" ";

		if (System.getProperty("file.separator").equals("\\")) {
			usageIntro += "-D\"java.util.logging.config.file=conf/logging.properties\"";
		}
		else {
			usageIntro += "-Djava.util.logging.config.file=conf/logging.properties";
		}

		usageIntro += " com.maxprograms.tmxvalidation.TMXValidator [-help] [-version] -tmx";

		if (System.getProperty("file.separator").equals("\\")) {
			usageIntro += "\n\n";
		}
		else {
			usageIntro += " tmxFile\n\n";
		}

		String help = usageIntro
				+ "Where:\n\n"
				+ "   -help:       (optional) Display this help information and exit\n"
				+ "   -version:    (optional) Display version & build information and exit\n"
				+ "   -tmx:        validate single TMX file or entire directory of TMX files\n";

		if (System.getProperty("file.separator").equals("\\")) {
			help += "                Note: Specify file or directory path in tmx.properties using 'tmx' as a property name\n\n";
		}
		else {
			help += "   tmxFile:     path of TMX file or directory of TMX files\n\n";
		}

		System.out.println(help);
	}

	private static String[] fixPath(String[] args) {
		List<String> result = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (arg.startsWith("-")) {
				if (current.length() > 0) {
					result.add(current.toString().trim());
					current = new StringBuilder();
				}
				result.add(arg);
			} else {
				current.append(' ');
				current.append(arg);
			}
		}
		if (current.length() > 0) {
			result.add(current.toString().trim());
		}
		return result.toArray(new String[result.size()]);
	}
}
