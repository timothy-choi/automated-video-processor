package api.templates;

import org.socialsignin.spring.data.dynamodb.repository.EnableScan;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

@EnableScan
public interface TemplateRepository extends CrudRepository<Template, PrimaryKey>{
    List<Templates> findByUserId(String userId);
}
