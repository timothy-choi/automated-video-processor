package API.templates;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.google.api.services.slides.v1.model.Presentation;
import com.google.api.services.slides.v1.model.BatchUpdatePresentationResponse;
import org.springframework.web.reactive.function.client.WebClient;

import api.templates.PrimaryKey;
import api.templates.TemplateRepository;
import api.WebClientConfig;

import java.time.LocalDate;
import java.util.*;

@RestController
public class TemplateController {
    @Autowired
    TemplateRepository templateRepository;

    private Map getTemplate(Template template) {
        Map temp = new HashMap();
        temp.put("primaryKeyUserId", template.getUserId());
        temp.put("primaryKeyTemplateId", template.getTemplateId());
        temp.put("name", template.getName());
        temp.put("slides", template.getSlides());
        temp.put("text", template.getText());
        temp.put("partitions", template.getPartitions());
        temp.put("slideDuration", template.getSlideDuration());
        temp.put("shapes", template.getShapes());
        temp.put("images", template.getImages());
        return temp;
    }

    @GetMapping("/template/{userId}/{publicDisplay}")
    public ResponseEntity getAllUserTemplates(@PathVariable("userId") string userId, @PathVariable("publicDisplay") bool publicDisplay) {
        List<Templates> allUserTemplates = templateRepository.findByUserId(userId);
        if (publicDisplay) {
            List<Map> res = new ArrayList<Map>();
            for (Template temp : allUserTemplates) {
                res.add(getTemplate(temp));
            }
            return ResponseEntity.ok(res);
        }
        return ResponseEntity.ok(allUserTemplates);
    }

    @GetMapping("/template/{userId}/{templateId}/{publicDisplay}")
    public ResponseEntity getReqUserTemplate(@PathVariable("userId") string userId, @PathVariable("templateId") string templateId, @PathVariable("publicDisplay") bool publicDisplay) {
        PrimaryKey primKey = new PrimaryKey();
        primKey.setUserId(userId);
        primKey.setTemplateId(templateId);

        Template found = null;

        Optional<Template> selectedTemplate = templateRepository.findByUserId(primKey);
        selectedTemplate.ifPresent(
            template -> found = template
        );

        if (found) {
            if (publicDisplay) {
                Map res = getTemplate(found);
                return ResponseEntity.ok(res);
            }
            return ResponseEntity.ok(found);
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/template/presentation/{presentationId}") 
    public ResponseEntity getSelectedPresentation(@PathVariable("presentationId") string presentationId) {
        try {
            Presentation currPresentation = TemplateOperations.getPresentation(presentationId);
            return ResponseEntity.ok(currPresentation);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }    

    @PostMapping("/template")
    public ResponseEntity createNewTemplate(@RequestBody String name) {
        String templateId = TemplateOperations.createTemplate(name);
        Template newTemplate = new Template()
            .setName(name)
            .setUserId()
            .setTemplateId(templateId)
            .createSlides()
            .createPartitions()
            .createText()
            .createSlideDuration()
            .createShapes()
            .createImages();
        
        return ResponseEntity.ok(templateRepository.save(newTemplate));
    }

    @PostMapping("/template/slide")
    public ResponseEntity createNewSlide(@RequestBody Map<String, String> requestInfo) {
        String presentationId = requestInfo.get("presId");
        String slideId = requestInfo.get("slideId"); 
        bool color = Boolean.parseBoolean(requestInfo.get("color")); 
        double red = Double.parseDouble(requestInfo.get("red"));
        double blue = Double.parseDouble(requestInfo.get("blue"));
        double green = Double.parseDouble(requestInfo.get("green"));
        try {
            TemplateOperations.createSlide(presentationId, slideId, color, red, blue, green);
            Template corrTemplate = WebClientConfig.webClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/templates/{userId}/{templateId}/{publicDisplay}")
                .build(requestInfo.get("userId"), requestId.get("templateId"), false))
            .retrieve()
            .bodyToMono(Template.class);
            corrTemplate.addSlide(slideId);
            templateRepository.save(corrTemplate);
            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/template/slide/{presentationId}/{slideId}")
    public ResponseEntity getSpecifiedSlide(@PathVariable("presentationId") String presentationId, @PathVariable("slideId") String slideId) {
        try {
            Page slide = TemplateOperations.getSlide(presentationId, slideId);
            return ResponseEntity.ok(slide);
        }
        catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/template/slide/{userId}/{presentationId}/{slideId}")
    public ResponseEntity deleteSlide(@PathVariable("userId") String userId, @PathVariable("presentationId") String presentationId, @PathVariable("slideId") String slideId) {
        try {
            TemplateOperations.deleteObject(presentationId, slideId);

            Template corrTemplate = WebClientConfig.webClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/templates/{userId}/{templateId}/{publicDisplay}")
                .build(userId, presentationId, false))
            .retrieve()
            .bodyToMono(Template.class);

            corrTemplate.deleteSlide(slideId);

            templateRepository.save(corrTemplate);

            return ResponseEntity.ok();

        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/template/text")
    public ResponseEntity addText(@RequestBody Map<String, String> requestInfo) {
        String presentationId = requestInfo.get("presId");
        String slideId = requestInfo.get("SlideId");
        String shape = requestInfo.get("shape");
        String shapeId = requestInfo.get("shapeId");
        bool transparent = Boolean.parseBoolean(requestInfo.get("transparent"));
        double magnitude = Double.parseDouble(requestInfo.get("magnitude"));
        double red = Double.parseDouble(requestInfo.get("red"));
        double blue = Double.parseDouble(requestInfo.get("blue"));
        double green = Double.parseDouble(requestInfo.get("green"));
        String textBoxId = requestInfo.get("textBoxId");
        String text = requestInfo.get("text");
        String prevText = requestInfo.get("prevText");
        try {
            //create a text box that has same color as that of slide
            TemplateOperations.createShape(presentationId, shapeId, shape, slideId, transparent, magnitude, red, green, blue);
            //add text into box
            TemplateOperations.addText(presentationId, textBoxId, text, prevText);

            Template corrTemplate = WebClientConfig.webClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/templates/{userId}/{templateId}/{publicDisplay}")
                .build(requestInfo.get("userId"), requestId.get("templateId"), false))
            .retrieve()
            .bodyToMono(Template.class);
            corrTemplate.addText(slideId + " " + textBoxId + " " + text);
            templateRepository.save(corrTemplate);

            return ResponseEntity.ok();
        }
        catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/template/editText")
    public ResponseEntity editText(@RequestBody Map<String, String> requestInfo) {
        try {
            TemplateOperations.replaceText(requestInfo.get("presId"), requestInfo.get("shapeId"), requestInfo.get("newText"));

            Template corrTemplate = WebClientConfig.webClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/templates/{userId}/{templateId}/{publicDisplay}")
                .build(requestInfo.get("userId"), requestId.get("templateId"), false))
            .retrieve()
            .bodyToMono(Template.class);

            List<String> allText = corrTemplate.getText();

            List<Int> textIndices = new ArrayList<Int>();

            for (int i = 0; i < allText.size(); ++i) {
                if (allText.get(i).contains(requestInfo.get("shapeId"))) {
                    textIndices.add(i);
                }
            }

            corrTemplate.editText(textIndices.get(0), requestInfo.get("newText"));

            for (int j = 1; j < textIndices.size(); ++j) {
                corrTemplate.deleteText(textIndices.get(j));
            }
            
            templateRepository.save(corrTemplate);

            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/template/text/{userId}/{templateId}/{textId}/{textBoxId}")
    public ResponseEntity deleteGivenText(@PathVariable("userId") String userId, @PathVariable("templateId") String templateId, @PathVariable("textId") String textId, @PathVariable("textBoxId") String textBoxId) {
        try {
            TemplateOperations.deleteText(templateId, textId);

            Template corrTemplate = WebClientConfig.webClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/templates/{userId}/{templateId}/{publicDisplay}")
                .build(requestInfo.get("userId"), requestId.get("templateId"), false))
            .retrieve()
            .bodyToMono(Template.class);

            List<String> allText = corrTemplate.getText();

            for (int i = 0; i < allText.size(); ++i) {
                if (allText.get(i).contains(textBoxId)) {
                    corrTemplate.deleteText(i);
                }
            }

            templateRepository.save(corrTemplate);

            TemplateOperations.deleteObject(templateId, textBoxId);

            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }


    @PostMapping("/template/shape")
    public ResponseEntity addShape(@RequestBody Map<String, String> requestInfo) {
        try {
            String presentationId = requestInfo.get("presId");
            String slideId = requestInfo.get("SlideId");
            String shape = requestInfo.get("shape");
            String shapeId = requestInfo.get("shapeId");
            bool transparent = Boolean.parseBoolean(requestInfo.get("transparent"));
            double magnitude = Double.parseDouble(requestInfo.get("magnitude"));
            double red = Double.parseDouble(requestInfo.get("red"));
            double blue = Double.parseDouble(requestInfo.get("blue"));
            double green = Double.parseDouble(requestInfo.get("green"));

            TemplateOperations.createShape(presentationId, shapeId, shape, slideId, transparent, magnitude, red, green, blue);

            Template corrTemplate = WebClientConfig.webClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/templates/{userId}/{templateId}/{publicDisplay}")
                .build(requestInfo.get("userId"), requestId.get("templateId"), false))
            .retrieve()
            .bodyToMono(Template.class);
            corrTemplate.addShape(slideId + " " + shapeId);
            templateRepository.save(corrTemplate);
            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/template/shape/{userId}/{presentationId}/{shapeId}")
    public ResponseEntity deleteGivenShape(@PathVariable("userId") String userId, @PathVariable("presentationId") String presentationId, @PathVariable("shapeId") String shapeId) {
        try {
            TemplateOperations.deleteObject(presentationId, shapeId);

            Template corrTemplate = WebClientConfig.webClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/templates/{userId}/{templateId}/{publicDisplay}")
                .build(userId, presentationId, false))
            .retrieve()
            .bodyToMono(Template.class);

            List<String> shapes = corrTemplate.getShapes();

            for (int i = 0; i < shapes.size(); ++i) {
                if (shapes.get(i).equals(shapeId)) {
                    corrTemplate.deleteShapes(i);
                    break;
                }
            }

            templateRepository.save(corrTemplate);

            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/template/image") 
    public ResponseEntity addImage(@RequestBody Map<String, String> requestInfo) {
        try {
            TemplateOperations.addImage(requestInfo.get("presId"), requestInfo.get("slideId"), requestInfo.get("imageUrl"), Double.parseDouble(requestInfo.get("magnitude")));

            Template corrTemplate = WebClientConfig.webClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/templates/{userId}/{templateId}/{publicDisplay}")
                .build(requestInfo.get("userId"), requestId.get("templateId"), false))
            .retrieve()
            .bodyToMono(Template.class);

            corrTemplate.addImage(slideId + " " + imageUrl);

            templateRepository.save(corrTemplate);

            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/template/image/{userId}/{presentationId}/{imageId}/{imageUrl}")
    public ResponseEntity deleteGivenImage(@PathVariable("userId") String userId, @PathVariable("presentationId") String presentationId, @PathVariable("imageId") String imageId, @PathVariable("imageUrl") String imageUrl) {
        try {
            TemplateOperations.deleteObject(presentationId, imageId);

            Template corrTemplate = WebClientConfig.webClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/templates/{userId}/{templateId}/{publicDisplay}")
                .build(userId, presentationId, false))
            .retrieve()
            .bodyToMono(Template.class);

            List<String> images = corrTemplate.getImages();

            for (int i = 0; i < images.size(); ++i) {
                if (images.get(i).equals(imageUrl)) {
                    corrTemplate.deleteImages(i);
                    break;
                }
            }

            templateRepository.save(corrTemplate);

            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/template/move/slide")
    public ResponseEntity moveSlide(@RequestBody Map<String, String> requestInfo) {
        try {
            TemplateOperations.moveSlide(requestInfo.get("presId"), requestInfo.get("slideId"), Integer.parseInt(requestInfo.get("newIndex")));

            Template corrTemplate = WebClientConfig.webClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/templates/{userId}/{templateId}/{publicDisplay}")
                .build(requestInfo.get("userId"), requestId.get("templateId"), false))
            .retrieve()
            .bodyToMono(Template.class);

            List<String> allSlides = corrTemplate.getSlides();

            int index = -1;

            for (int i = 0; i < allSlides.size(); ++i) {
                if (allSlides.get(i).equals(requestInfo.get("slideId"))) {
                    index = i;
                    break;
                }
            }

            corrTemplate.moveSlide(index, Integer.parseInt(requestInfo.get("newIndex")));

            templateRepository.save(corrTemplate);

            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/template/partition")
    public ResponseEntity addPartition(@RequestBody Map<String, String> requestInfo) {
        try {
            Template corrTemplate = WebClientConfig.webClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/templates/{userId}/{templateId}/{publicDisplay}")
                .build(requestInfo.get("userId"), requestId.get("templateId"), false))
            .retrieve()
            .bodyToMono(Template.class);

            corrTemplate.addPartition(Integer.parseInt(requestInfo.get("slideIndex")));

            templateRepository.save(corrTemplate);

            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/template/partition/{userId}/{templateId}/{slideNum}")
    public ResponseEntity deletePartition(@PathVariable("userId") String userId, @PathVariable("templateId") String templateId, @PathVariable("slideNum") Int slideNum) {
        try {
            Template corrTemplate = WebClientConfig.webClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/templates/{userId}/{templateId}/{publicDisplay}")
                .build(userId, templateId, false))
            .retrieve()
            .bodyToMono(Template.class);

            corrTemplate.deletePartition(slideNum);

            templateRepository.save(corrTemplate);

            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/template/slideDuration")
    public ResponseEntity addSlideDuration(@RequestBody Map<String, String> requestInfo) {
        try {
            Template corrTemplate = WebClientConfig.webClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/templates/{userId}/{templateId}/{publicDisplay}")
                .build(requestInfo.get("userId"), requestId.get("templateId"), false))
            .retrieve()
            .bodyToMono(Template.class);

            corrTemplate.addSlideDuration(Integer.parseInt(requestInfo.get("duration")));

            templateRepository.save(corrTemplate);

            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/template/slideDuration/{userId}/{templateId}/{slideIndex}/{newTime}")
    public ResponseEntity editSlideDuration(@PathVariable("userId") String userId, @PathVariable("templateId") String templateId, @PathVariable("slideIndex") Int slideIndex, @PathVariable("newTime") Int newTime) {
        try {
            Template corrTemplate = WebClientConfig.webClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/templates/{userId}/{templateId}/{publicDisplay}")
                .build(userId, templateId, false))
            .retrieve()
            .bodyToMono(Template.class);

            corrTemplate.editSlideDuration(slideIndex, newTime);

            templateRepository.save(corrTemplate);

            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/template/slideDuration/{userId}/{templateId}/{slideIndex}")
    public ResponseEntity deleteSlideDuration(@PathVariable("userId") String userId, @PathVariable("templateId") String templateId, @PathVariable("slideIndex") Int slideIndex) {
        try {
            Template corrTemplate = WebClientConfig.webClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/templates/{userId}/{templateId}/{publicDisplay}")
                .build(userId, templateId, false))
            .retrieve()
            .bodyToMono(Template.class);

            corrTemplate.deleteSlideDuration(slideIndex);

            templateRepository.save(corrTemplate);

            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
