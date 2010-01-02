package net.sf.egonet.web.page;

import net.sf.egonet.model.Question;
import net.sf.egonet.persistence.Interviewing;
import net.sf.egonet.persistence.Interviews;
import net.sf.egonet.persistence.Studies;

import org.apache.wicket.markup.html.basic.MultiLineLabel;
import org.apache.wicket.markup.html.link.Link;

public class InterviewingQuestionIntroPage extends EgonetPage {

	private EgonetPage previousPage, nextPage;
	
	public InterviewingQuestionIntroPage(
			Long interviewId, String text, EgonetPage previous, EgonetPage next) 
	{
		super(Studies.getStudyForInterview(interviewId).getName()+ " - Interviewing "
				+Interviews.getEgoNameForInterview(interviewId)
				+" (respondent #"+interviewId+")");

        this.previousPage = previous;
        this.nextPage = next;
        
        add(new MultiLineLabel("text", text));

		add(new Link("backwardLink") {
			public void onClick() {
				if(previousPage != null) {
					setResponsePage(previousPage);
				}
			}
		});
		
		add(new Link("forwardLink") {
			public void onClick() {
				if(nextPage != null) {
					setResponsePage(nextPage);
				}
			}
		});
	}

	public static EgonetPage possiblyReplaceNextQuestionPageWithPreface(
			Long interviewId, EgonetPage proposedNextPage,
			Question earlyQuestion, Question lateQuestion,
			EgonetPage earlyPage, EgonetPage latePage)
	{
		String preface =
			Interviewing.getPrefaceBetweenQuestions(earlyQuestion, lateQuestion);
		return preface == null ? proposedNextPage : 
			new InterviewingQuestionIntroPage(interviewId,preface,earlyPage,latePage);
	}
}
