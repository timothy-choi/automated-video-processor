package api.videoProcessing;

import java.util.*;

import com.google.api.services.slides.v1.model.Page;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.opencv.opencv_core.CvType;
import org.bytedeco.opencv.opencv_core.IplImage;
import org.bytedeco.javacpp.opencv_core;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.Frame;

import java.io.File;
import java.io.IOException;


public class SlideVideoConverter {
    public static String combineSlidesIntoVideo(List<Page> pageSlides, List<String> imageSlides, List<String> animations, List<Int> durations, int n) {
        String outputPath = System.getProperty("user.home") + "/Downloads/" + "partition" + n + ".mp4";
        List<File> imageFiles = new ArrayList<File>();
        List<BufferedImage> bufferedImages = new ArrayList<BufferedImages>();

        for (String slide : imageSlides) {
            imageFiles.add(new File(slide));
        }

        try {
            FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputPath, 800, 600);
            recorder.setVideoCodecName("libx264");

            recorder.start();

            for (File img : imageFiles) {
                try {
                    bufferedImages.add(ImageIO.read(img));
                } catch (IOException e) {
                    throw new Exception("Couldn't process slides");
                }
            }

            String firstSlideId = pageSlides.get(0).getObjectId();

            List<BufferedImage> animationFrames = new ArrayList<BufferedImage>();

            bool animationIncluded = false;

            for (String animation : animations) {
                if (animation.contains(firstSlideId)) {
                    string animationType = animation.substring(animation.lastIndexOf(" ") + 1);
                    animationIncluded = true;
                    switch(animationType) {
                        case "fade-in":
                            animationFrames = createFadeInAnimation(bufferedImages.get(0), null);
                            break;
                        case "slide":
                            animationFrames = createSlideAnimation(bufferedImages.get(0), null);
                            break;
                        case "zoom":
                            animationFrames = createZoomAnimation(bufferedImages.get(0), null);
                            break;
                        default:
                            break; 
                    }
                    break;
                }
            }

            if (animationIncluded) {
                for (BufferedImage frame : animationFrames) {

                }
            }

            for (BufferedImage bufferedImg : bufferedImages) {

            }

            recorder.stop();
            recorder.release();

        } catch (Exception e) {
            throw new Exception("Couldn't create video partition");
        }

        return outputPath;
    }

    public static List<BufferedImage> createFadeInAnimation(BufferedImage currImage, BufferedImage nextImage) {

    }

    public static List<BufferedImage> createSlideAnimation(BufferedImage currImage, BufferedImage nextImage) {

    }

    public static List<BufferedImage> createZoomAnimation(BufferedImage currImage, BufferedImage nextImage) {

    }
}
