package api.accounts;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.org.List;

public interface AccountsRepository extends MongoRepository<Accounts, String> {
    Accounts findByAccountId(Long accountId);

    Accounts findByUsername(String username);

    List<Accounts> findAll();
}
