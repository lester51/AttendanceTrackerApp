package com.dat.sys;

public class AttendanceEntry {
    private String name;
    private String course;
    private String timeIn;
    private String timeOut;
    private boolean isLate;
    private boolean isSelected;

    public AttendanceEntry(String name, String course, String timeIn) {
        this.name = name;
        this.course = course;
        this.timeIn = timeIn;
        this.timeOut = "";
        this.isLate = false;
        this.isSelected = false;
    }

    // Getters and Setters
    public String getName() { return name; }
    public String getCourse() { return course; }
    public String getTimeIn() { return timeIn; }
    public String getTimeOut() { return timeOut; }
    public void setTimeOut(String timeOut) { this.timeOut = timeOut; }
    public boolean isLate() { return isLate; }
    public void setLate(boolean late) { isLate = late; }
    public boolean isSelected() { return isSelected; }
    public void setSelected(boolean selected) { isSelected = selected; }
}
