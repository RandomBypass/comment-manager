package edu.random.bypass;

import edu.random.bypass.controller.AppController;
import edu.random.bypass.model.CommentsModel;
import edu.random.bypass.view.MainView;

import javax.swing.*;

public class YouTubeCommentSearchApp {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                e.printStackTrace();
            }
            CommentsModel model = new CommentsModel();
            MainView view = new MainView();
            AppController controller = new AppController(model, view);
            view.setVisible(true);
            controller.start();
        });
    }
}