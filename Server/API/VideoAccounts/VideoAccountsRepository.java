package api.videoProcessing;

import org.socialsignin.spring.data.dynamodb.repository.EnableScan;
import org.springframework.data.repository.CrudRepository;

@EnableScan
public interface VideoAccountsRepository extends CrudRepository<VideoAccounts, PrimaryKey> {
    VideoAccounts findVideoAcctById(String userId);
}
