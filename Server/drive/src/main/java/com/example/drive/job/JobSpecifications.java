package com.example.drive.job;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.example.drive.job.domain.Job;
import com.example.drive.job.domain.Operation;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

final class JobSpecifications {

	private JobSpecifications() {
	}

	static Specification<Job> matching(JobListQuery query) {
		return (root, criteriaQuery, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (query.status() != null) {
				predicates.add(cb.equal(root.get("status"), query.status()));
			}
			if (query.priority() != null) {
				predicates.add(cb.equal(root.get("priority"), query.priority()));
			}
			if (query.createdAfter() != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), query.createdAfter()));
			}
			if (query.createdBefore() != null) {
				predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), query.createdBefore()));
			}
			if (query.operationType() != null) {
				Subquery<UUID> subquery = criteriaQuery.subquery(UUID.class);
				Root<Operation> operation = subquery.from(Operation.class);
				subquery.select(operation.get("id"))
						.where(
								cb.equal(operation.get("job").get("id"), root.get("id")),
								cb.equal(operation.get("type"), query.operationType())
						);
				predicates.add(cb.exists(subquery));
			}
			if (predicates.isEmpty()) {
				return cb.conjunction();
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}
}
