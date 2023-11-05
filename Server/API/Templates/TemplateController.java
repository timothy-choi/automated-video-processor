package API.templates;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}
