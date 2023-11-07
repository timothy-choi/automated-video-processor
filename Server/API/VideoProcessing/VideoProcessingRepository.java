package api.videoProcessing;

import org.socialsignin.spring.data.dynamodb.repository.EnableScan;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

@EnableScan
public interface VideoProcessingRepository extends CrudRepository<VideoProcessing, PrimaryKey>{
    List<VideoProcessing> findByUserId(String userId);
}

