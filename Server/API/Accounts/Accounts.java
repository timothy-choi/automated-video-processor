package api.accounts;

import javax.persistence.GeneratedValue;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Date;  


@Document(collection = "accounts")
public class Accounts {
    @Id
    private @GeneratedValue Long accountId;
    private String name;
    private String username;

    private Date joined;
    private String email;

    private Int NumberOfVideosMade;
    private List<Long> VideosCreated;

    private List<Long> TemplatesCreated;
    
    Accounts() {}

    Accounts(String name, String username, String email) {
        this.accountId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        this.name = name;
        this.username = username;
        Date date = new Date();
        this.joined = date;
        this.email = email;
        this.NumberOfVideosMade = 0;
        this.VideosCreated = new ArrayList<Long>();
        this.TemplatesCreated = new ArrayList<Long>();
    }

    public Long getId() {
        return this.accountId;
    }

    public String getName() {
        return this.name;
    }

    public String getEmail() {
        return this.email;
    }

    public Date getJoinedDate() {
        return this.joined;
    }

    public String getUsername() {
        return this.username;
    }

    public void setUsername(String newUsername) {
        this.username = newUsername;
    }

    public void setNumVideos(int change) {
        this.NumberOfVideosMade += change;
    }

    public int getNumVideos() {
        return this.NumberOfVideosMade;
    }

    public void addVideo(Long videoId) {
        this.VideosCreated.add(videoId);
    }

    public List<Long> getVideos() {
        return this.VideosCreated;
    }

    public void addTemplate(Long templateId) {
        this.TemplatesCreated.add(templateId);
    }

    public List<Long> getTemplates() {
        return this.TemplatesCreated;
    }
}
