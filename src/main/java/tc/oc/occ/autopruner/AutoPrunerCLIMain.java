package tc.oc.occ.autopruner;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.File;
import java.util.concurrent.ExecutionException;

public class AutoPrunerCLIMain {
  public static void main(String[] args) throws ExecutionException, InterruptedException {
    CommandLine cmd = processOptions(args);
    if (cmd == null) return;

    if (cmd.hasOption("file")) {
      String filePath = cmd.getOptionValue("file");
      AutoPruner.pruneMCAFileLogger(filePath);
    } else if (cmd.hasOption("directory")) {
      String directoryPath = cmd.getOptionValue("directory");

      if (cmd.hasOption("threads")) {
        int threads = Integer.parseInt(cmd.getOptionValue("threads"));

        ThreadPoolAutoPruner threadPoolAutoPruner = new ThreadPoolAutoPruner(threads);
        threadPoolAutoPruner.recursivelyProcessFiles(new File(directoryPath), 0);
        threadPoolAutoPruner.close();
      } else {
        AutoPruner.recursivelyProcessFiles(new File(directoryPath), 0);
      }
    } else {
      new AutoPrunerGui().buildAndRunGui();
    }
  }

  private static CommandLine processOptions(String[] args) {
    Options options = new Options();

    Option fileOption = new Option(
        "f",
        "file",
        true,
        "Path for .mca file to prune");
    fileOption.setRequired(false);
    options.addOption(fileOption);

    Option directoryOption = new Option(
        "d",
        "directory",
        true,
        "Path for directory containing .mca files");
    directoryOption.setRequired(false);
    options.addOption(directoryOption);

    Option threadOption = new Option(
        "t",
        "threads",
        true,
        "Number of threads to use");
    threadOption.setRequired(false);
    options.addOption(threadOption);

    CommandLineParser parser = new DefaultParser();
    HelpFormatter formatter = new HelpFormatter();
    try {
      return parser.parse(options, args);
    } catch (ParseException e) {
      System.out.println(e.getMessage());
      formatter.printHelp("java -jar AutoPruner.jar", options);
      return null;
    }
  }
}
