package com.docreader.app.model;

public class RecentFile {
    public String uri;
    public String displayName;
    public String mimeType;
    public long lastOpened;

    public RecentFile(String uri, String displayName, String mimeType, long lastOpened) {
        this.uri = uri;
        this.displayName = displayName;
        this.mimeType = mimeType;
        this.lastOpened = lastOpened;
    }
}
