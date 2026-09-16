package cn.itcast.demo.jobplatform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;

@Component
public class InvitationExpiryJob {
    private final InvitationService invitations;
    private final boolean enabled;
    public InvitationExpiryJob(InvitationService invitations,@Value("${app.jobs.enabled:true}") boolean enabled) {
        this.invitations=invitations; this.enabled=enabled;
    }
    @Scheduled(fixedDelay=60000,initialDelay=15000)
    public void expire() {
        if(enabled) try { invitations.expire(); }
        catch(Exception e) { LoggerFactory.getLogger(getClass()).error("Invitation expiry failed",e); }
    }
}
