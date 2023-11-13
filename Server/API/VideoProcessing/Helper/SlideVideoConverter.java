package api.videoProcessing;

import java.util.*;

import javax.imageio.ImageIO;

import com.google.api.services.slides.v1.model.Page;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.opencv.opencv_core.IplImage;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.Frame;

import java.awt.image.BufferedImage;
import java.awt.Color;
import java.io.File;
import java.io.IOException;


public class SlideVideoConverter {
    public static String combineSlidesIntoVideo(List<Page> pageSlides, List<String> imageSlides, List<String> animations, List<Int> durations, int n, String filename) {
        String outputPath = System.getProperty("user.home") + "/Downloads/" + filename + ".mp4";
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
        List<BufferedImage> animateFrames = new List<BufferedImage>();

        int width = 800;
        int height = 600;
        int duration = 10;
        if (nextImage == null) {
            for (int index = 0; index < duration; ++index) {
                float alpha = (float) index / (float) duration;
                BufferedImage frame = new BufferedImage(width, height);
                Graphics2D g = frame.createGraphics();
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

                Color imgColor = new Color(currImage.getRGB(50,50));
                g.setColor(imgColor);

                int rectWidth = 50;
                int rectHeight = 50;
                int x = (frame.getWidth() - rectWidth) / 2;
                int y = (frame.getHeight() - rectHeight) / 2;
                g.fillRect(x, y, rectWidth, rectHeight);
                g.dispose();
                animateFrames.add(frame);
            }
        }
        else {
            width = currImage.getWidth();
            height = currImage.getHeight();
            for (int index = 0; index < duration; ++index) {
                float alpha = (float) index / (float) duration;
                BufferedImage frame = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
                Graphics2D g = frame.createGraphics();
                g.drawImage(currImage, 0, 0, null);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f - alpha));
                g.drawImage(nextImage, 0, 0, null);
                g.dispose();
                animateFrames.add(frame);
            }
        }
        return animateFrames;
    }

    public static List<BufferedImage> createSlideAnimation(BufferedImage currImage, BufferedImage nextImage) {
        List<BufferedImage> animateFrames = new List<BufferedImage>();
        
        int width = currImage.getWidth();
        int height = currImage.getHeight();
        int duration = 10;

        for (int index = 0; index < duration; ++index) {
            float alpha = (float) index / (float) duration;
            BufferedImage frame = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
            Graphics2D g = frame.createGraphics();
            g.drawImage(currImage, 0, 0, null);

            int transEffect = (int) (alpha * width);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            g.drawImage(nextImage, -transEffect, 0, null);
            g.dispose();
            animateFrames.add(frame);
        }
        return animateFrames;
    }

    public static List<BufferedImage> createZoomAnimation(BufferedImage currImage, BufferedImage nextImage) {
        List<BufferedImage> animateFrames = new List<BufferedImage>();
        
        int width = currImage.getWidth();
        int height = currImage.getHeight();
        int duration = 10;

        for (int index = 0; index < duration; ++index) {
            float alpha = 1.0f + (float) index / (float) duration;

            int scaledWidth = (int) (width * alpha);
            int scaledHeight = (int) (height * alpha);

            BufferedImage frame = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
            Graphics2D g = frame.createGraphics();
            g.drawImage(currImage, 0, 0, null);

            int offsetX = (width - scaledWidth) / 2;
            int offsetY = (height - scaledHeight) / 2;
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
            g.drawImage(nextImage, offsetX, offsetY, scaledWidth, scaledHeight, null);
            g.dispose();
            animateFrames.add(frame);
        }
        return animateFrames;
    }
}
