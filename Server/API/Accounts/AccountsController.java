package api.accounts;

import org.springframework.web.bind.annotation.RestController;

import API.Auth.Accounts;

import org.springframework.web.bind.annotation.RequestMapping;
import javax.servlet.http.HttpServletResponse;


@RestController
@RequestMapping("/accounts")
public class AccountsController {
    @Autowired
    private AccountsService accountsService;

    @GetMapping(value="/accounts")
    public ResponseEntity getAllAccounts() {
        List<Accounts> accts = accountsService.findAll();
        if (accts.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No accounts found");
        }
        return ResponseEntity.status(HttpStatus.OK).body(accts);
    }

    @GetMapping(value="/accounts/{accountId}")
    public ResponseEntity getAccountById(@PathVariable Long accountId) {
        Accounts acct = accountsService.findByAccountId(accountId);
        if (acct == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Account not found");
        }
        return ResponseEntity.status(HttpStatus.OK).body(acct);
    }

    @GetMapping(value="/accounts/{username}")
    public ResponseEntity getAccountByUsername(@PathVariable String username) {
        Accounts acct = accountsService.findByUsername(username);
        if (acct == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Account not found");
        }
        return ResponseEntity.status(HttpStatus.OK).body(acct);
    }

    @PostMapping(value="/accounts/create")
    public ResponseEntity createNewAccount(@RequestBody String name, @RequestBody String username, @RequestBody String email) {
        try {
            accountsService.createAccount(name, username, email);
        }
        catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Account Creation Failed");
        }
        return ResponseEntity("Signup successful", HttpStatus.OK);
    }

    @PutMapping(value = "/accounts/username/{currUsername}/{newUsername}")
    public ResponseEntity updateUsername(@PathVariable String username, @PathVariable String newUsername) {
        try {
            HttpServletResponse response = new HttpServletResponse();
            accountsService.updateUsername(username, newUsername, response);
        }
        catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Account not found");
        }
        return ResponseEntity("Username updated successfully", HttpStatus.OK);
    }

    @PutMapping(value = "/accounts/{username}/videos/count/{change}")
    public ResponseEntity addVideoCount(@PathVariable String username, @PathVariable Int change) {
        try {
            accountsService.addVideoCount(username, change);
        }
        catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Account not found");
        }
        return ResponseEntity("Video Count updated successfully", HttpStatus.OK);
    }

    @PostMapping(value = "/accounts/{username}/videos")
    public ResponseEntity addVideo(@PathVariable String username, @RequestBody Long videoId) {
        try {
            accountsService.addVideo(username, videoId);
        }
        catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Account not found");
        }
        return ResponseEntity("Video added successfully", HttpStatus.OK);
    }

    @DeleteMapping(value="/accounts/{accountId}")
    public ResponseEntity deleteAcct(@PathVariable Long accountId) {
        try {
            accountsService.deleteAccount(accountId);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Account not found");
        }
        return ResponseEntity("Account deleted successfully", HttpStatus.OK);
    }
}
