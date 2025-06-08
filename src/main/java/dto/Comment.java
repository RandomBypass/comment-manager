package dto;

// Comment data class
public record Comment(String user, String channel, String video, String text, String date) {
}