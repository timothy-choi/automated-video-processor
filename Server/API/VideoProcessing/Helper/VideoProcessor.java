package api.videoProcessing;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;

public class VideoProcessor {
    public static String combineAllVideos(List<String> allVideoPaths, String videoFilename) {
        String outputFile = System.getProperty("user.home") + "/Downloads/" + videoFilename + ".mp4";
        try {
            FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputFile, 800,600);

            recorder.start();

            recorder.stop();
            recorder.release();
        } catch (Exception e) {
            throw new Exception("Couldn't create video");
        }

        return outputFile;
    }
}
