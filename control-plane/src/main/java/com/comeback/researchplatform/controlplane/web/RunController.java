package com.comeback.researchplatform.controlplane.web;

import com.comeback.researchplatform.controlplane.planner.PlannerService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final PlannerService plannerService;
    private final JdbcTemplate jdbc;

    public RunController(PlannerService plannerService, JdbcTemplate jdbc) {
        this.plannerService = plannerService;
        this.jdbc = jdbc;
    }

    @PostMapping
    public SubmitRunResponse submit(@RequestBody SubmitRunRequest request) {
        return new SubmitRunResponse(plannerService.submit(request.question()));
    }

    @GetMapping("/{id}")
    public RunStatusResponse status(@PathVariable("id") UUID id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, question, status FROM runs WHERE id = ?",
                    (rs, rowNum) -> new RunStatusResponse(
                            UUID.fromString(rs.getString("id")), rs.getString("question"), rs.getString("status")),
                    id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No run with id " + id);
        }
    }
}
