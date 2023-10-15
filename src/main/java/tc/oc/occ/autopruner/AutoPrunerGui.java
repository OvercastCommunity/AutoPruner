package tc.oc.occ.autopruner;

import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.Dimension;
import java.io.File;

public class AutoPrunerGui {

  //TODO: Refactor and improve this class

  public AutoPrunerGui() {
    FlatLightLaf.setup();
  }

  public void buildAndRunGui() {
    SwingUtilities.invokeLater(() -> {
      JFrame frame = new JFrame("AutoPruner");
      frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

      JTextArea textArea = new JTextArea();
      textArea.setEditable(false);

      JScrollPane areaScrollPane = new JScrollPane(textArea);
      areaScrollPane.setVerticalScrollBarPolicy(
          JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
      areaScrollPane.setPreferredSize(new Dimension(900, 400));

      frame.add(areaScrollPane);
      frame.pack();
      frame.setVisible(true);

      JFileChooser fc = new JFileChooser();
      fc.setCurrentDirectory(new File(""));
      fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
      int option = fc.showDialog(frame, "Prune Map(s)");

      if (option == JFileChooser.APPROVE_OPTION) {
        File file = fc.getSelectedFile();
        textArea.append("Selected: " + file.getAbsolutePath() + "\n");

        new SwingWorker() {
          @Override
          protected Object doInBackground() {
            return AutoPruner.recursivelyProcessFiles(
                file,
                28,
                (message) -> textArea.append(message + "\n"));
          }
        }.execute();
      } else {
        System.exit(0);
      }
    });
  }

}
