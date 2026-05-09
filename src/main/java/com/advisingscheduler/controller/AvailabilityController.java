package com.advisingscheduler.controller;

import com.advisingscheduler.model.AvailabilitySlot;
import com.advisingscheduler.service.AvailabilityService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
public class AvailabilityController {

    private static final Logger logger = LoggerFactory.getLogger(AvailabilityController.class);
    private static final String SESSION_ADVISOR_ID   = "advisorId";
    private static final String SESSION_ADVISOR_NAME = "advisorName";

    private final AvailabilityService availabilityService;

    public AvailabilityController(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    // ── Public: available slots for clients ──────────────────────────────────
    @GetMapping("/slots")
    public String viewAvailableSlots(Model model) {
        logger.debug("GET /slots — fetching available slots for display");
        List<AvailabilitySlot> slots = availabilityService.getAvailableSlots();
        logger.info("GET /slots — returned {} available slot(s)", slots.size());
        model.addAttribute("slots", slots);
        return "slots";
    }

    // ── Advisor login ─────────────────────────────────────────────────────────
    @GetMapping("/advisor/login")
    public String showLoginForm(@RequestParam(required = false) String error,
                                HttpSession session, Model model) {
        if (session.getAttribute(SESSION_ADVISOR_ID) != null) {
            return "redirect:/manage-slots"; // already logged in
        }
        if (error != null) model.addAttribute("error", "Invalid username or password.");
        return "advisor-login";
    }

    @PostMapping("/advisor/login")
    public String processLogin(@RequestParam String username,
                               @RequestParam String password,
                               HttpSession session) {
        logger.info("Advisor login attempt: username={}", username);
        Optional<Map<String, Object>> result = availabilityService.login(username, password);
        if (result.isEmpty()) {
            return "redirect:/advisor/login?error=true";
        }
        Map<String, Object> advisor = result.get();
        String fullName = advisor.get("first_name") + " " + advisor.get("last_name");
        session.setAttribute(SESSION_ADVISOR_ID,   ((Number) advisor.get("advisor_id")).intValue());
        session.setAttribute(SESSION_ADVISOR_NAME, fullName);
        logger.info("Advisor logged in: {} (id={})", fullName, advisor.get("advisor_id"));
        return "redirect:/manage-slots";
    }

    @PostMapping("/advisor/logout")
    public String logout(HttpSession session) {
        String name = (String) session.getAttribute(SESSION_ADVISOR_NAME);
        logger.info("Advisor logged out: {}", name);
        session.invalidate();
        return "redirect:/";
    }

    // ── Manage slots (login-gated) ────────────────────────────────────────────
    @GetMapping("/manage-slots")
    public String manageSlots(@RequestParam(required = false) String success,
                              @RequestParam(required = false) String error,
                              HttpSession session, Model model) {
        Integer advisorId = (Integer) session.getAttribute(SESSION_ADVISOR_ID);
        if (advisorId == null) {
            return "redirect:/advisor/login";
        }
        logger.debug("GET /manage-slots — advisorId={}", advisorId);
        List<AvailabilitySlot> slots = availabilityService.getSlotsByAdvisor(advisorId);
        model.addAttribute("slots",       slots);
        model.addAttribute("advisorName", session.getAttribute(SESSION_ADVISOR_NAME));
        if (success != null) model.addAttribute("success", success);
        if (error   != null) model.addAttribute("error",   error);
        return "manage-slots";
    }

    @PostMapping("/manage-slots/add")
    public String addSlot(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime startTime,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime endTime,
            HttpSession session, Model model) {
        Integer advisorId = (Integer) session.getAttribute(SESSION_ADVISOR_ID);
        if (advisorId == null) {
            return "redirect:/advisor/login";
        }
        logger.info("POST /manage-slots/add — advisorId={} startTime={} endTime={}", advisorId, startTime, endTime);
        try {
            if (!endTime.isAfter(startTime)) {
                throw new IllegalArgumentException("End time must be after start time.");
            }
            availabilityService.addSlot(advisorId, startTime, endTime);
            return "redirect:/manage-slots?success=Slot+added+successfully";
        } catch (Exception e) {
            logger.warn("Failed to add slot: {}", e.getMessage());
            List<AvailabilitySlot> slots = availabilityService.getSlotsByAdvisor(advisorId);
            model.addAttribute("slots",       slots);
            model.addAttribute("advisorName", session.getAttribute(SESSION_ADVISOR_NAME));
            model.addAttribute("error",       e.getMessage());
            return "manage-slots";
        }
    }

    @PostMapping("/manage-slots/delete")
    public String deleteSlot(@RequestParam int slotId, HttpSession session) {
        if (session.getAttribute(SESSION_ADVISOR_ID) == null) {
            return "redirect:/advisor/login";
        }
        logger.info("POST /manage-slots/delete — slotId={}", slotId);
        try {
            availabilityService.deleteSlot(slotId);
            return "redirect:/manage-slots?success=Slot+deleted";
        } catch (IllegalStateException e) {
            logger.warn("Could not delete slot {}: {}", slotId, e.getMessage());
            return "redirect:/manage-slots?error=" + e.getMessage().replace(" ", "+");
        }
    }
}
