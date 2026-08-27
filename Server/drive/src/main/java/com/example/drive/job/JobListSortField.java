package com.example.drive.job;

public enum JobListSortField {
	CREATED_AT("createdAt"),
	UPDATED_AT("updatedAt");

	private final String property;

	JobListSortField(String property) {
		this.property = property;
	}

	public String property() {
		return property;
	}
}
