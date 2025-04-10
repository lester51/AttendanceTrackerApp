package com.dat.sys;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import fi.iki.elonen.NanoHTTPD;

public class MainActivity extends AppCompatActivity {

    private TableLayout attendanceTable;
    private List<AttendanceEntry> attendanceList = new ArrayList<>();
    private Set<String> deviceSet = new HashSet<>();
    private String classStartTime;
    private int lateThreshold;
    private boolean isTimeSet = false;
    private SimpleHTTPD server;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Check and request permissions based on Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Android 11+
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivityForResult(intent, 2);
            }
        } else { // Android 10 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            }
        }

        attendanceTable = findViewById(R.id.attendanceTable);
        Button btnSetTime = findViewById(R.id.btnSetTime);
        Button btnStart = findViewById(R.id.btnStart);
        Button btnEnd = findViewById(R.id.btnEnd);
        Button btnExportExcel = findViewById(R.id.btnExportExcel);
        Button btnDelete = findViewById(R.id.btnDelete);
        Button btnDeleteAll = findViewById(R.id.btnDeleteAll);

        btnSetTime.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					showSetTimeDialog();
				}
			});

        btnStart.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (!isTimeSet) {
						Toast.makeText(MainActivity.this, "Set time first!", Toast.LENGTH_SHORT).show();
						return;
					}
					startServerAndShowLink();
				}
			});

        btnEnd.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					endAttendance();
				}
			});

        btnExportExcel.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					exportToCsv();
				}
			});

        btnDelete.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					deleteSelectedEntries();
				}
			});

        btnDeleteAll.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					deleteAllEntries();
				}
			});
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 2) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                Toast.makeText(this, "Manage All Files permission granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Manage perprmission denied. Please enable it in Settings.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showSetTimeDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_set_time, null);
        builder.setView(dialogView);

        final EditText etClassTime = dialogView.findViewById(R.id.etClassTime);
        final EditText etLateThreshold = dialogView.findViewById(R.id.etLateThreshold);

        builder.setPositiveButton("Set Time", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					String time = etClassTime.getText().toString();
					String threshold = etLateThreshold.getText().toString();
					if (time.isEmpty() || threshold.isEmpty()) {
						Toast.makeText(MainActivity.this, "Fill all fields!", Toast.LENGTH_SHORT).show();
						return;
					}
					int thresholdValue;
					try {
						thresholdValue = Integer.parseInt(threshold);
					} catch (NumberFormatException e) {
						Toast.makeText(MainActivity.this, "Invalid late threshold!", Toast.LENGTH_SHORT).show();
						return;
					}
					if (thresholdValue > 60) {
						Toast.makeText(MainActivity.this, "Late threshold max 60 minutes!", Toast.LENGTH_SHORT).show();
						return;
					}
					classStartTime = time;
					lateThreshold = thresholdValue;
					isTimeSet = true;
					Toast.makeText(MainActivity.this, "Time set: " + time + ", Late threshold: " + threshold + " mins", Toast.LENGTH_LONG).show();
				}
			});

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void startServerAndShowLink() {
        try {
            server = new SimpleHTTPD(8080, this);
            server.start();
            String ip = getLocalIpAddress();
            String link = "http://" + ip + ":8080";
            android.util.Log.d("SERVER_LINK", "Generated link: " + link);
            showLinkDialog(link);
        } catch (IOException e) {
            android.util.Log.e("SERVER_ERROR", "Failed to start server: " + e.getMessage());
            Toast.makeText(this, "Failed to start server: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showLinkDialog(String link) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Time In");

        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_time_in, null);
        builder.setView(dialogView);

        TextView tvLink = dialogView.findViewById(R.id.tvLink);
        tvLink.setText("Link: " + link);

        ImageView ivQrCode = dialogView.findViewById(R.id.ivQrCode);
        try {
            Bitmap qrCodeBitmap = generateQrCode(link, 200, 200);
            ivQrCode.setImageBitmap(qrCodeBitmap);
        } catch (WriterException e) {
            Toast.makeText(this, "Failed to generate QR code!", Toast.LENGTH_SHORT).show();
        }

        builder.setPositiveButton("Close", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					dialog.dismiss();
				}
			});
        builder.show();
    }

    private Bitmap generateQrCode(String text, int width, int height) throws WriterException {
        MultiFormatWriter writer = new MultiFormatWriter();
        BitMatrix bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                bitmap.setPixel(x, y, bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        return bitmap;
    }

    private void endAttendance() {
        if (attendanceList.isEmpty()) {
            Toast.makeText(this, "No entries to end!", Toast.LENGTH_SHORT).show();
            return;
        }
        String currentTime = new SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(new Date());
        for (AttendanceEntry entry : attendanceList) {
            entry.setTimeOut(currentTime);
        }
        updateTable();
        if (server != null) {
            server.stop();
            server = null;
            Toast.makeText(this, "Attendance ended and server stopped", Toast.LENGTH_SHORT).show();
        }
        isTimeSet = false;
        classStartTime = null;
        lateThreshold = 0;
    }

    private void exportToCsv() {
        if (attendanceList.isEmpty()) {
            Toast.makeText(this, "No entries to export!", Toast.LENGTH_SHORT).show();
            return;
        }
        new ExportCsvTask().execute();
    }

    private class ExportCsvTask extends AsyncTask<Void, Void, String> {
        @Override
        protected String doInBackground(Void... voids) {
            FileOutputStream fos = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    return "Error: Please grant 'Manage All Files' permission in Settings";
                }

                File externalStorageDir = Environment.getExternalStorageDirectory();
                File dir = new File(externalStorageDir, "Attendance CSV List");

                if (!dir.exists()) {
                    if (!dir.mkdirs()) {
                        android.util.Log.e("EXPORT_CSV", "Failed to create directory: " + dir.getAbsolutePath());
                        return "Error: Failed to create directory";
                    }
                }

                String dateTime = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(new Date());
                String fileName = "Attendance_" + dateTime + ".csv";
                File file = new File(dir, fileName);
                android.util.Log.d("EXPORT_CSV", "Exporting to: " + file.getAbsolutePath());

                StringBuilder csvContent = new StringBuilder();
                csvContent.append("Name,Course,TimeIn,TimeOut\n");

                for (AttendanceEntry entry : attendanceList) {
                    String name = entry.getName() != null ? entry.getName() : "N/A";
                    String course = entry.getCourse() != null ? entry.getCourse() : "N/A";
                    String timeIn = entry.getTimeIn() != null ? entry.getTimeIn() : "N/A";
                    String timeOut = entry.getTimeOut() != null ? entry.getTimeOut() : "N/A";

                    name = escapeCsvField(name);
                    course = escapeCsvField(course);
                    timeIn = escapeCsvField(timeIn);
                    timeOut = escapeCsvField(timeOut);

                    csvContent.append(String.format("%s,%s,%s,%s\n", name, course, timeIn, timeOut));
                }

                fos = new FileOutputStream(file);
                fos.write(csvContent.toString().getBytes());
                return "Exported to " + file.getAbsolutePath();
            } catch (Exception e) {
                e.printStackTrace();
                String errorMessage = e.getMessage() != null ? e.getMessage() : e.toString();
                android.util.Log.e("EXPORT_CSV", "Export failed: " + errorMessage, e);
                return "Export failed: " + errorMessage;
            } finally {
                try {
                    if (fos != null) fos.close();
                } catch (IOException e) {
                    android.util.Log.e("EXPORT_CSV", "Error closing FileOutputStream: " + e.getMessage(), e);
                }
            }
        }

        @Override
        protected void onPostExecute(String result) {
            Toast.makeText(MainActivity.this, result, Toast.LENGTH_LONG).show();
        }

        private String escapeCsvField(String field) {
            if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
                field = "\"" + field.replace("\"", "\"\"") + "\"";
            }
            return field;
        }
    }

    private void deleteSelectedEntries() {
        attendanceList.removeIf(new java.util.function.Predicate<AttendanceEntry>() {
				@Override
				public boolean test(AttendanceEntry entry) {
					return entry.isSelected();
				}
			});
        updateTable();
    }

    private void deleteAllEntries() {
        attendanceList.clear();
        deviceSet.clear();
        updateTable();
    }

    private void updateTable() {
        attendanceTable.removeAllViews();
        for (final AttendanceEntry entry : attendanceList) {
            final TableRow row = new TableRow(this);
            row.setPadding(8, 8, 8, 8);
            row.setBackgroundColor(entry.isSelected() ? 0xFF888888 : (entry.isLate() ? 0xFFFFA500 : 0xFF00FF00));

            TextView tvName = new TextView(this);
            tvName.setText(entry.getName() != null ? entry.getName() : "N/A");
            tvName.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f));
            tvName.setPadding(8, 8, 8, 8);

            TextView tvCourse = new TextView(this);
            tvCourse.setText(entry.getCourse() != null ? entry.getCourse() : "N/A");
            tvCourse.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f));
            tvCourse.setPadding(8, 8, 8, 8);

            TextView tvTimeIn = new TextView(this);
            tvTimeIn.setText(entry.getTimeIn() != null ? entry.getTimeIn() : "N/A");
            tvTimeIn.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f));
            tvTimeIn.setPadding(8, 8, 8, 8);

            TextView tvTimeOut = new TextView(this);
            tvTimeOut.setText(entry.getTimeOut() != null ? entry.getTimeOut() : "N/A");
            tvTimeOut.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f));
            tvTimeOut.setPadding(8, 8, 8, 8);

            row.addView(tvName);
            row.addView(tvCourse);
            row.addView(tvTimeIn);
            row.addView(tvTimeOut);

            row.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						entry.setSelected(!entry.isSelected());
						row.setBackgroundColor(entry.isSelected() ? 0xFF888888 : (entry.isLate() ? 0xFFFFA500 : 0xFF00FF00));
					}
				});

            attendanceTable.addView(row);
        }
    }

    private String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                if (intf.getName().startsWith("wlan")) {
                    for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                        InetAddress inetAddress = enumIpAddr.nextElement();
                        if (!inetAddress.isLoopbackAddress() && inetAddress.getHostAddress().contains(".")) {
                            String ip = inetAddress.getHostAddress();
                            android.util.Log.d("IP_ADDRESS", "Found IP: " + ip);
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.e("IP_ADDRESS", "Error getting IP: " + e.getMessage());
        }
        android.util.Log.e("IP_ADDRESS", "No valid IP found, defaulting to 127.0.0.1");
        return "127.0.0.1";
    }

    private class SimpleHTTPD extends NanoHTTPD {
        private Context context;
        private MainActivity activity;

        public SimpleHTTPD(int port, MainActivity activity) throws IOException {
            super(null, port);
            this.context = activity;
            this.activity = activity;
        }

        @Override
        public Response serve(IHTTPSession session) {
            String uri = session.getUri();
            String remoteIp = session.getRemoteIpAddress();
            String method = session.getMethod().toString();
            android.util.Log.d("SimpleHTTPD", "Request received - Method: " + method + ", URI: " + uri + ", From: " + remoteIp);

            if (uri.equals("/")) {
                android.util.Log.d("SimpleHTTPD", "Serving form to: " + remoteIp);
                return newFixedLengthResponse(Response.Status.OK, MIME_HTML, getFormHtml());
            } else if (uri.equals("/submit")) {
                android.util.Log.d("SimpleHTTPD", "Processing submission from: " + remoteIp);
                try {
                    Map<String, String> postData = new HashMap<>();
                    if (session.getMethod() == Method.POST) {
                        session.parseBody(postData);
                        android.util.Log.d("SimpleHTTPD", "Raw POST body: " + postData.toString());
                    }

                    Map<String, List<String>> params = session.getParameters();
                    android.util.Log.d("SimpleHTTPD", "Parsed params: " + params.toString());

                    String name = params.containsKey("name") && !params.get("name").isEmpty() ? params.get("name").get(0) : null;
                    String course = params.containsKey("course") && !params.get("course").isEmpty() ? params.get("course").get(0) : null;

                    android.util.Log.d("SimpleHTTPD", "Extracted: name=" + name + ", course=" + course + ", deviceId (IP)=" + remoteIp);

                    if (name == null || course == null || name.isEmpty() || course.isEmpty()) {
                        android.util.Log.w("SimpleHTTPD", "Invalid submission: Name or Course missing");
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Error: Name and Course are required!");
                    }

                    if (deviceSet.contains(remoteIp)) {
                        android.util.Log.w("SimpleHTTPD", "Device already submitted: " + remoteIp);
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Error: Device already submitted!");
                    }

                    deviceSet.add(remoteIp);
                    String timeIn = new SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(new Date());
                    AttendanceEntry entry = new AttendanceEntry(name, course, timeIn);

                    SimpleDateFormat sdf = new SimpleDateFormat("hh:mm:ss a", Locale.getDefault());
                    SimpleDateFormat sdfStart = new SimpleDateFormat("hh:mm a", Locale.getDefault());
                    try {
                        Date startTime = sdfStart.parse(classStartTime);
                        Date currentTime = sdf.parse(timeIn);

                        java.util.Calendar startCal = java.util.Calendar.getInstance();
                        startCal.setTime(startTime);
                        java.util.Calendar currentCal = java.util.Calendar.getInstance();
                        currentCal.setTime(currentTime);

                        java.util.Calendar today = java.util.Calendar.getInstance();
                        startCal.set(today.get(java.util.Calendar.YEAR), today.get(java.util.Calendar.MONTH), today.get(java.util.Calendar.DAY_OF_MONTH));
                        currentCal.set(today.get(java.util.Calendar.YEAR), today.get(java.util.Calendar.MONTH), today.get(java.util.Calendar.DAY_OF_MONTH));

                        startTime = startCal.getTime();
                        currentTime = currentCal.getTime();

                        android.util.Log.d("SimpleHTTPD", "Start Time: " + startTime + ", Current Time: " + currentTime);
                        long diff = currentTime.getTime() - startTime.getTime();
                        long diffMinutes = diff / (60 * 1000);
                        android.util.Log.d("SimpleHTTPD", "Time Difference (minutes): " + diffMinutes + ", Threshold: " + lateThreshold);
                        if (diffMinutes > lateThreshold) {
                            entry.setLate(true);
                            android.util.Log.d("SimpleHTTPD", "Entry marked as late: " + timeIn);
                        } else {
                            android.util.Log.d("SimpleHTTPD", "Entry on time: " + timeIn);
                        }
                    } catch (ParseException e) {
                        android.util.Log.e("SimpleHTTPD", "Time Parsing Error: " + e.getMessage(), e);
                    }

                    attendanceList.add(entry);
                    android.util.Log.d("SimpleHTTPD", "Entry added: " + entry.getName() + ", " + entry.getCourse());
                    activity.runOnUiThread(new Runnable() {
							@Override
							public void run() {
								activity.updateTable();
							}
						});
                    return newFixedLengthResponse(Response.Status.OK, MIME_HTML,
												  "<html><body><h1>Success</h1><p>Attendance recorded for " + name + " (" + course + ") at " + timeIn + ".</p></body></html>");
                } catch (Exception e) {
                    android.util.Log.e("SimpleHTTPD", "Error: " + e.getMessage(), e);
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: " + e.getMessage());
                }
            }
            android.util.Log.d("SimpleHTTPD", "Not found for: " + remoteIp);
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        }

        private String getFormHtml() {
            return "<!DOCTYPE html>" +
				"<html lang='en'>" +
				"<head>" +
				"    <meta charset='UTF-8'>" +
				"    <meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
				"    <title>Attendance Form</title>" +
				"    <style>" +
				"        body {" +
				"            display: flex;" +
				"            justify-content: center;" +
				"            align-items: center;" +
				"            height: 100vh;" +
				"            margin: 0;" +
				"            background-color: #f0f0f0;" +
				"            font-family: Arial, sans-serif;" +
				"        }" +
				"        .form-container {" +
				"            background-color: white;" +
				"            padding: 20px;" +
				"            border-radius: 8px;" +
				"            box-shadow: 0 2px 10px rgba(0, 0, 0, 0.1);" +
				"            width: 300px;" +
				"            text-align: center;" +
				"        }" +
				"        h1 {" +
				"            margin-bottom: 20px;" +
				"            font-size: 24px;" +
				"            color: #333;" +
				"        }" +
				"        input[type='text'] {" +
				"            width: 100%;" +
				"            padding: 10px;" +
				"            margin: 10px 0;" +
				"            border: 1px solid #ccc;" +
				"            border-radius: 4px;" +
				"            box-sizing: border-box;" +
				"        }" +
				"        input[type='submit'] {" +
				"            background-color: #4CAF50;" +
				"            color: white;" +
				"            padding: 10px;" +
				"            border: none;" +
				"            border-radius: 4px;" +
				"            cursor: pointer;" +
				"            width: 100%;" +
				"            font-size: 16px;" +
				"        }" +
				"        input[type='submit']:hover {" +
				"            background-color: #45a049;" +
				"        }" +
				"        @media (max-width: 400px) {" +
				"            .form-container {" +
				"                width: 90%;" +
				"            }" +
				"        }" +
				"    </style>" +
				"</head>" +
				"<body>" +
				"    <div class='form-container'>" +
				"        <h1>Attendance Form</h1>" +
				"        <form action='/submit' method='post' enctype='application/x-www-form-urlencoded'>" +
				"            <label for='name'>Full Name:</label>" +
				"            <input type='text' id='name' name='name' required><br>" +
				"            <label for='course'>Course/Year:</label>" +
				"            <input type='text' id='course' name='course' required><br>" +
				"            <input type='submit' value='Submit'>" +
				"        </form>" +
				"    </div>" +
				"</body>" +
				"</html>";
        }
    }
}
