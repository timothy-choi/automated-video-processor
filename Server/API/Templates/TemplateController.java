package API.templates;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import api.templates.PrimaryKey;
import api.templates.TemplateRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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
}
