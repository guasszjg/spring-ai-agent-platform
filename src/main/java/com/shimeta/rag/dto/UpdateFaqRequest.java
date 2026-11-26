package com.shimeta.rag.dto;

import java.util.List;

public class UpdateFaqRequest {
    private String question;
    private String answer;
    private String category;
    private String contentType;
    private List<String> imageUrls;
    private List<String> videoUrls;
    private Boolean enabled;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public List<String> getImageUrls() {
        return imageUrls;
    }

    public void setImageUrls(List<String> imageUrls) {
        this.imageUrls = imageUrls;
    }

    public List<String> getVideoUrls() {
        return videoUrls;
    }

    public void setVideoUrls(List<String> videoUrls) {
        this.videoUrls = videoUrls;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
