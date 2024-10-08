package com.example.drive;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.util.TableUtils;
import Server.API.Templates;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;


public class DynamoDBStart {
    public void createTables(AmazonDynamoDB newDb) {
        DynamoDBMapper newMapper = new DynamoDBMapper(newDb);
        CreateTableRequest createTemplatesReq = newMapper.generateCreateTableRequest(Templates.class)
        .withProvisionedThroughput(new ProvisionedThroughput(1l, 1l));
        TableUtils.createTableIfNotExists(newDb, createTemplatesReq);
    }
}
