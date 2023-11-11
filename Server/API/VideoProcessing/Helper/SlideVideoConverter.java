package api.videoProcessing;

import java.util.*;

import javax.imageio.ImageIO;

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

            String firstSlideId = pageSlides.get(n).getObjectId();

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
                BufferedImage editedFirstImage = bufferedImages.get(n);
                for (BufferedImage frame : animationFrames) {
                    editedFirstImage = blendFrames(editedFirstImage, frame);
                }

                try {
                    ImageIO.write(editedFirstImage, "png", new File(imageSlides.get(0)));
                    bufferedImages.set(0, editedFirstImage);
                } catch (IOException e) {
                    throw new Exception("Couldn't create animations");
                }
            }

            for (int i = 0; i < bufferedImages.size(); ++i) {
                BufferedImage currImg = bufferedImages.get(i);

                for (int j = 0; j < SlideDuration.get(i); ++j) {
                    IplImage iplImg = convertToIplImage(bufferedImages.get(i));
                    Frame frame = new OpenCVFrameConverter.ToIplImage().convert(iplImg);
                    recorder.record(frame);
                }
                if (i < bufferedImages.size() - 1) {
                    String slideId = pageSlides.get(i).getObjectId();
                    if (!animations.get(i).contains(slideId)) {
                        continue;
                    }
                    BufferedImage nextImg = bufferedImages.get(i+1);
                    String animationType = animation.get(i).substring(animation.lastIndexOf(" ") + 1);
                    switch(animationType) {
                        case "fade-in":
                            animationFrames = createFadeInAnimation(currImg, nextImg);
                            break;
                        case "slide":
                            animationFrames = createSlideAnimation(currImg, nextImg);
                            break;
                        case "zoom":
                            animationFrames = createZoomAnimation(currImg, nextImg);
                            break;
                        default:
                            break; 
                    }
                    for (BufferedImage animationFrame : animationFrames) {
                        IplImage iplImg = convertToIplImage(animationFrame);
                        Frame frame = new OpenCVFrameConverter.ToIplImage().convert(iplImg);
                        recorder.record(frame);
                    }
                }
            }

            recorder.stop();
            recorder.release();

        } catch (Exception e) {
            throw new Exception("Couldn't create video partition");
        }

        return outputPath;
    }

    public static BufferedImage blendFrames(BufferedImage baseImage, BufferedImage overlayFrame) {
        BufferedImage blended = new BufferedImage(baseImage.getWidth(), baseImage.getHeight(), BufferedImage.TYPE_4BYTE_ABGR);

        Graphics2D drawing = blended.createGraphics();
        drawing.drawImage(baseImage, 0, 0, null);
        drawing.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        drawing.drawImage(overlayFrame, 0, 0, null);
        drawing.dispose();

        return blended;
    }

    public static IplImage convertToIplImage(BufferedImage img) {
        ToIplImage iplConverter = new OpenCVFrameConverter.ToIplImage();
        Java2DFrameConverter javaConverter = new Java2DFrameConverter();
        Frame newFrame = javaConverter.convert(img);
        IplImage result =  iplConverter.convert(newFrame);
        return result;
    }

    public static List<BufferedImage> createFadeInAnimation(BufferedImage currImage, BufferedImage nextImage) {

    }

    public static List<BufferedImage> createSlideAnimation(BufferedImage currImage, BufferedImage nextImage) {

    }

    public static List<BufferedImage> createZoomAnimation(BufferedImage currImage, BufferedImage nextImage) {

    }
}
