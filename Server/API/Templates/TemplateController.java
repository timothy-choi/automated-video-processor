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

    @GetMapping("/template/{userId}")
    public ResponseEntity getAllUserTemplates(@PathVariable("userId") string userId) {
        List<Templates> allUserTemplates = templateRepository.findByUserId(userId);
        return ResponseEntity.ok(allUserTemplates);
    }

    @GetMapping("/template/{userId}/{templateId}")
    public ResponseEntity getReqUserTemplate(@PathVariable("userId") string userId, @PathVariable("templateId") string templateId) {
        PrimaryKey primKey = new PrimaryKey();
        primKey.setUserId(userId);
        primKey.setTemplateId(templateId);

        Template found = null;

        Optional<Template> selectedTemplate = templateRepository.findByUserId(primKey);
        selectedTemplate.ifPresent(
            template -> found = template
        );

        if (found) {
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
                .path("/templates/{userId}/{templateId}")
                .build(requestInfo.get("userId"), requestId.get("templateId")))
            .retrieve()
            .bodyToMono(Template.class);
            corrTemplate.addSlide(slideId);
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
                .path("/templates/{userId}/{templateId}")
                .build(requestInfo.get("userId"), requestId.get("templateId")))
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
                .path("/templates/{userId}/{templateId}")
                .build(requestInfo.get("userId"), requestId.get("templateId")))
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
                .path("/templates/{userId}/{templateId}")
                .build(requestInfo.get("userId"), requestId.get("templateId")))
            .retrieve()
            .bodyToMono(Template.class);
            corrTemplate.addShape(slideId + " " + shapeId);
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
                .path("/templates/{userId}/{templateId}")
                .build(requestInfo.get("userId"), requestId.get("templateId")))
            .retrieve()
            .bodyToMono(Template.class);

            corrTemplate.addImage(slideId + " " + imageUrl);

            templateRepository.save(corrTemplate);

            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
