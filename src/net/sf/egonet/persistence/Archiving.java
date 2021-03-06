package net.sf.egonet.persistence;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.sf.egonet.model.Alter;
import net.sf.egonet.model.Answer;
import net.sf.egonet.model.Entity;
import net.sf.egonet.model.Expression;
import net.sf.egonet.model.Interview;
import net.sf.egonet.model.Question;
import net.sf.egonet.model.QuestionOption;
import net.sf.egonet.model.Study;
import net.sf.egonet.model.Question.QuestionType;
import net.sf.egonet.web.panel.NumericLimitsPanel.NumericLimitType;

import net.sf.functionalj.tuple.Pair;
import net.sf.functionalj.tuple.Triple;

import org.dom4j.Attribute;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.SAXReader;
import org.dom4j.io.XMLWriter;
import org.hibernate.Session;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultiset;

public class Archiving {

	public static String getStudyXML(final Study study) {
		return new DB.Action<String>() {
			public String get() {
				return getStudyXML(session, study,false);
			}
		}.execute();
	}
	public static String getRespondentDataXML(final Study study) {
		return new DB.Action<String>() {
			public String get() {
				return getStudyXML(session, study,true);
			}
		}.execute();
	}
	
	private static void addText(Element root, String elementName, String elementText) {
		root.addElement(elementName).addText(elementText == null ? "" : elementText);
	}
	
	public static String getStudyXML(Session session, Study study, Boolean includeInterviews) {
		try {
			Document document = DocumentHelper.createDocument();
			Element studyNode = addStudyNode(document, study);
			Element questionsNode = studyNode.addElement("questions");
			Multiset<QuestionType> questionsOfTypeSoFar = TreeMultiset.create();
			for(Question question : Questions.getQuestionsForStudy(session, study.getId(), null)) {
				addQuestionNode(questionsNode,question, 
						Options.getOptionsForQuestion(session, question.getId()),
						questionsOfTypeSoFar.count(question.getType()));
				questionsOfTypeSoFar.add(question.getType());
			}
			Element expressionsNode = studyNode.addElement("expressions");
			for(Expression expression : Expressions.forStudy(session, study.getId())) {
				addExpressionNode(expressionsNode,expression);
			}
			if(includeInterviews) {
				Element interviewsNode = studyNode.addElement("interviews");
				for(Interview interview : Interviews.getInterviewsForStudy(session, study.getId())) {
					addInterviewNode(interviewsNode,interview,
							Alters.getForInterview(session, interview.getId()),
							Answers.getAnswersForInterview(session, interview.getId()));
				}
			}
			return formatXMLDocument(document);
		} catch(Exception ex) {
			throw new RuntimeException("Failed to get XML for study "+study, ex);
		}
	}

	public static Study loadStudyXML(final Study study, final String studyXML) {
		return new DB.Action<Study>() {
			public Study get() {
				return loadStudyXML(session, study,studyXML,true,false);
			}
		}.execute();
	}
	
	public static Study loadRespondentXML(final Study study, final String studyXML) {
		return new DB.Action<Study>() {
			public Study get() {
				return loadStudyXML(session, study,studyXML,false,true);
			}
		}.execute();
	}
	
	public static Study loadStudyXML(final Session session, 
			Study studyToUpdate, String studyXML, 
			Boolean updateStudy, Boolean updateRespondentData)
	{
		try {
			Document document = new SAXReader().read(new StringReader(studyXML));
			Element studyElement = document.getRootElement();
			
			Study study = studyToUpdate;
			if(study == null) {
				study = new Study();
			} else {
				Long xmlKey = attrLong(studyElement,"key");
				if(xmlKey == null || ! xmlKey.equals(study.getRandomKey())) {
					throw new RuntimeException("Trying to import incompatible study.");
				}
			}
			DB.save(session, study);
			
			List<Element> expressionElements = studyElement.element("expressions").elements("expression");
			List<Expression> expressions = Expressions.forStudy(session, study.getId());
			Map<Long,Long> remoteToLocalExpressionId = createRemoteToLocalMap(
					expressions, expressionElements, updateStudy, updateStudy,
					fnCreateExpression(), fnDeleteExpression(session));
			
			if(updateStudy) {
				updateStudyFromNode(session,study,studyElement,remoteToLocalExpressionId);
			}
			
			List<Element> questionElements = studyElement.element("questions").elements("question");
			List<Question> questions = Questions.getQuestionsForStudy(session, study.getId(), null);
			Map<Long,Long> remoteToLocalQuestionId = createRemoteToLocalMap(
					questions, questionElements, updateStudy, updateStudy,
					fnCreateQuestion(), fnDeleteQuestion(session));
			
			Map<Long,Long> remoteToLocalOptionId = Maps.newTreeMap();

			Map<Long,Question> localIdToQuestion = indexById(questions);
			for(Element questionElement : questionElements) {
				Long remoteQuestionId = attrId(questionElement);
				Long localQuestionId = remoteToLocalQuestionId.get(remoteQuestionId);
				if(localQuestionId != null) {
					Question question = localIdToQuestion.get(localQuestionId);
					if(question == null) {
						String msg = "LocalQuestionId is "+localQuestionId+" but no local question? ";
						msg += "Remote to local map: ";
						for(Map.Entry<Long,Long> keyVal : remoteToLocalQuestionId.entrySet()) {
							msg += " <"+keyVal.getKey()+","+keyVal.getValue()+">, ";
						}
						msg += "Ids in local map: ";
						for(Long key : localIdToQuestion.keySet()) {
							msg += " "+key+", ";
						}
						throw new RuntimeException(msg);
					}
					if(updateStudy) {
						updateQuestionFromNode(session,question,questionElement,
								study.getId(),remoteToLocalExpressionId);
					}
					List<Element> optionElements = questionElement.elements("option");
					List<QuestionOption> optionEntities = Options.getOptionsForQuestion(session, question.getId());
					Map<Long,Long> optionIdMap = createRemoteToLocalMap(
							optionEntities,optionElements,updateStudy,updateStudy,
							fnCreateOption(),fnDeleteOption(session));
					remoteToLocalOptionId.putAll(optionIdMap);
					if(updateStudy) {
						Map<Long,QuestionOption> idToOptionEntity = indexById(optionEntities);
						for(Element optionElement : optionElements) {
							Long localId = remoteToLocalOptionId.get(attrId(optionElement));
							if(localId != null) {
								QuestionOption optionEntity = idToOptionEntity.get(localId);
								updateOptionFromNode(session,optionEntity,optionElement,question.getId());
							}
						}
					}
				}
			}
			
			if(updateStudy) {
				Map<Long,Expression> localIdToExpression = indexById(expressions);
				for(Element expressionElement : expressionElements) {
					Long localExpressionId = remoteToLocalExpressionId.get(attrId(expressionElement));
					if(localExpressionId != null) {
						Expression expression = localIdToExpression.get(localExpressionId);
						updateExpressionFromNode(session,expression,expressionElement,
								study.getId(),remoteToLocalQuestionId,remoteToLocalOptionId,
								remoteToLocalExpressionId);
					}
				}
			}
			
			if(updateRespondentData) {
				// Import interviews
				List<Element> interviewElements = studyElement.element("interviews").elements("interview");
				List<Interview> interviews = Interviews.getInterviewsForStudy(session, study.getId());
				Map<Long,Long> remoteToLocalInterviewId = createRemoteToLocalMap(
						interviews, 
						interviewElements, true, false,
						fnCreateInterview(), null);
				Map<Long,Interview> localIdToInterview = indexById(interviews);
				for(Element interviewElement : interviewElements) {
					Long localInterviewId = remoteToLocalInterviewId.get(attrId(interviewElement));
					Interview interview = localIdToInterview.get(localInterviewId);
					updateInterviewFromNode(session,interview,interviewElement,study.getId());
					
					// Import alters
					List<Element> alterElements = interviewElement.element("alters").elements("alter");
					List<Alter> alterEntities = Alters.getForInterview(session, interview.getId());
					Map<Long,Long> remoteToLocalAlterId = createRemoteToLocalMap(
							alterEntities,
							alterElements, true, true,
							fnCreateAlter(), fnDeleteAlter(session));
					Map<Long,Alter> localIdToAlter = indexById(alterEntities);
					for(Element alterElement : alterElements) {
						Long localAlterId = remoteToLocalAlterId.get(attrId(alterElement));
						Alter alter = localIdToAlter.get(localAlterId);
						updateAlterFromNode(session,alter,alterElement,interview.getId());
					}
					
					// Import answers
					List<Element> answerElements = interviewElement.element("answers").elements("answer");
					List<Answer> answerEntities = Answers.getAnswersForInterview(session, interview.getId());
					Map<Long,Long> remoteToLocalAnswerId = createRemoteToLocalMap(
							answerEntities,
							answerElements, true, false,
							fnCreateAnswer(), null);
					Map<Long,Answer> localIdToAnswer = indexById(answerEntities);
					for(Element answerElement : answerElements) {
						Long localAnswerId = remoteToLocalAnswerId.get(attrId(answerElement));
						Answer answer = localIdToAnswer.get(localAnswerId);
						updateAnswerFromNode(session,answer,answerElement,
								study.getId(),interview.getId(),
								remoteToLocalQuestionId,remoteToLocalAlterId,remoteToLocalOptionId);
					}
				}
			}
			
			return study;
		} catch(Exception ex) {
			throw new RuntimeException("Failed to load XML for study "+studyToUpdate, ex);
		}
	}
	
	private static <E extends Entity> Map<Long,Long> 
	createRemoteToLocalMap(
			List<E> entities, List<Element> elements,
			Boolean shouldCreate, Boolean shouldDelete,
			Function<Object,E> creator, Function<E,Object> deleter)
	{
		// Index entities by key
		Set<Long> keys = Sets.newTreeSet();
		Map<Long,E> keyToEntity = Maps.newTreeMap();
		for(E entity : entities) {
			keyToEntity.put(entity.getRandomKey(), entity);
			keys.add(entity.getRandomKey());
		}
		Map<Long,Element> keyToElement = Maps.newTreeMap();
		for(Element element : elements) {
			Long key = attrLong(element,"key");
			keyToElement.put(key, element);
			keys.add(key);
		}
		
		// Create map from remote id to local id
		Map<Long,Long> remoteToLocalId = Maps.newTreeMap();
		for(Long key : keys) {
			if(shouldCreate && ! keyToEntity.containsKey(key)) {
				E entity = creator.apply(null);
				entity.setRandomKey(key);
				DB.save(entity);
				keyToEntity.put(key, entity);
				entities.add(entity);
			}
			if(shouldDelete && ! keyToElement.containsKey(key)) {
				E entity = keyToEntity.remove(key);
				deleter.apply(entity);
				entities.remove(entity);
			}
			Entity entity = keyToEntity.get(key);
			Element element = keyToElement.get(key);
			if(entity != null && element != null) {
				remoteToLocalId.put(attrId(element), entity.getId());
			}
		}
		
		return remoteToLocalId;
	}

	private static Element addStudyNode(Document document, Study study) {
		Element studyNode = document.addElement("study")
			.addAttribute("id", study.getId()+"")
			.addAttribute("name", study.getName())
			.addAttribute("key", study.getRandomKey()+"")
			.addAttribute("minAlters", study.getMinAlters()+"")
			.addAttribute("maxAlters", study.getMaxAlters()+"")
			.addAttribute("valueDontKnow", study.getValueDontKnow())
			.addAttribute("valueLogicalSkip", study.getValueLogicalSkip())
			.addAttribute("valueNotYetAnswered", study.getValueNotYetAnswered())
			.addAttribute("valueRefusal", study.getValueRefusal())
			.addAttribute("adjacencyExpressionId", study.getAdjacencyExpressionId()+"");
		addText(studyNode,"introduction",study.getIntroduction());
		addText(studyNode,"egoIdPrompt",study.getEgoIdPrompt());
		addText(studyNode,"alterPrompt",study.getAlterPrompt());
		addText(studyNode,"conclusion",study.getConclusion());
		return studyNode;
	}
	
	private static void updateStudyFromNode(Session session, Study study, Element studyElement, 
			Map<Long,Long> remoteToLocalExpressionId) 
	{
		study.setName(attrString(studyElement,"name"));
		study.setRandomKey(attrLong(studyElement,"key"));
		study.setMinAlters(attrInt(studyElement,"minAlters"));
		study.setMaxAlters(attrInt(studyElement,"maxAlters"));
		study.setValueDontKnow(attrString(studyElement,"valueDontKnow"));
		study.setValueLogicalSkip(attrString(studyElement,"valueLogicalSkip"));
		study.setValueNotYetAnswered(attrString(studyElement,"valueNotYetAnswered"));
		study.setValueRefusal(attrString(studyElement,"valueRefusal"));
		Long remoteAdjacencyId = attrLong(studyElement,"adjacencyExpressionId");
		study.setAdjacencyExpressionId(
				remoteAdjacencyId == null ? null : 
					remoteToLocalExpressionId.get(remoteAdjacencyId));
		study.setIntroduction(attrText(studyElement,"introduction"));
		study.setEgoIdPrompt(attrText(studyElement,"egoIdPrompt"));
		study.setAlterPrompt(attrText(studyElement,"alterPrompt"));
		study.setConclusion(attrText(studyElement,"conclusion"));
		DB.save(session, study);
	}
	
	private static Element addQuestionNode(Element questionsNode, 
			Question question, List<QuestionOption> options, Integer ordering) 
	{
		Element questionNode = questionsNode.addElement("question")
			.addAttribute("id", question.getId()+"")
			.addAttribute("title", question.getTitle())
			.addAttribute("key", question.getRandomKey()+"")
			.addAttribute("answerType", question.getAnswerTypeDB())
			.addAttribute("subjectType", question.getTypeDB())
			.addAttribute("askingStyleList", question.getAskingStyleList()+"")
			// in case ordering == null, I use the order they were pulled from the DB
			.addAttribute("ordering", ordering+"")
			.addAttribute("answerReasonExpressionId", question.getAnswerReasonExpressionId()+"");
		if ( question.getAnswerType()==Answer.AnswerType.NUMERICAL ) {
			System.out.println ( "Archiving addQuestionNode " + question.getMinLimitType().toString());
			questionNode.addAttribute("minLimitType", question.getMinLimitType().toString())
			.addAttribute("minLiteral", question.getMinLiteral()+"")
			.addAttribute("minPrevQues", question.getMinPrevQues())
			.addAttribute("maxLimitType", question.getMaxLimitType().toString())
			.addAttribute("maxLiteral", question.getMaxLiteral()+"")
			.addAttribute("maxPrevQues", question.getMaxPrevQues());	
		}
		addText(questionNode,"preface",question.getPreface()); 
		addText(questionNode,"prompt",question.getPrompt());
		addText(questionNode,"citation",question.getCitation());
		for(Integer i = 0; i < options.size(); i++) {
			addOptionNode(questionNode,options.get(i),i);
		}
		return questionNode;
	}
	
	/**
	 * when importing older xml files, the data regarding the numeric checking
	 * and ranges might not be present.  If an exception is thrown we will just
	 * assign default values.
	 * @param session
	 * @param question
	 * @param node
	 * @param studyId
	 * @param remoteToLocalExpressionId
	 */
	private static void updateQuestionFromNode(Session session, Question question, Element node, 
			Long studyId, Map<Long,Long> remoteToLocalExpressionId) 
	{
		String strLimitType;
		
		question.setStudyId(studyId);
		question.setTitle(attrString(node,"title"));
		question.setAnswerTypeDB(attrString(node,"answerType"));
		question.setTypeDB(attrString(node,"subjectType"));
		question.setAskingStyleList(attrBool(node,"askingStyleList"));
		question.setOrdering(attrInt(node,"ordering"));
		question.setPreface(attrText(node,"preface"));
		question.setPrompt(attrText(node,"prompt"));
		question.setCitation(attrText(node,"citation"));

		if ( question.getAnswerType()==Answer.AnswerType.NUMERICAL ) {
			try {
			strLimitType = attrText(node,"minLimitType");
			if (strLimitType==null || strLimitType.length()==0 )
				question.setMinLimitType(NumericLimitType.NLT_NONE);
			else
				question.setMinLimitType(NumericLimitType.valueOf(strLimitType));
			question.setMinLiteral(attrInt(node,"minLiteral"));
			question.setMinPrevQues(attrText(node,"minPrevQues"));
			strLimitType = attrText(node,"maxLimitType");
			if (strLimitType==null || strLimitType.length()==0 )
				question.setMaxLimitType(NumericLimitType.NLT_NONE);
			else
			    question.setMaxLimitType(NumericLimitType.valueOf(strLimitType));
			question.setMaxLiteral(attrInt(node,"maxLiteral"));
			question.setMaxPrevQues(attrText(node,"maxPrevQues"));
			} catch ( java.lang.RuntimeException rte ) {
				// if just about anything went wrong, goto defaults
				question.setMinLimitType(NumericLimitType.NLT_NONE);
				question.setMinLiteral(0);
				question.setMinPrevQues("");
				question.setMaxLimitType(NumericLimitType.NLT_NONE);
				question.setMaxLiteral(1000);
				question.setMaxPrevQues("");
			}
		}
		
		
		Long remoteReasonId = attrLong(node,"answerReasonExpressionId");
		question.setAnswerReasonExpressionId(
				remoteReasonId == null ? null : 
					remoteToLocalExpressionId.get(remoteReasonId));
		DB.save(session, question);
	}
	
	private static Element addOptionNode(Element questionNode, 
			QuestionOption option, Integer ordering) 
	{
		return questionNode.addElement("option")
			.addAttribute("id", option.getId()+"")
			.addAttribute("name", option.getName())
			.addAttribute("key", option.getRandomKey()+"")
			.addAttribute("value", option.getValue())
			.addAttribute("ordering", ordering+"");
	}
	
	private static void updateOptionFromNode(Session session, QuestionOption option, Element node, 
			Long questionId) 
	{
		option.setQuestionId(questionId);
		option.setName(attrString(node,"name"));
		option.setValue(attrString(node,"value"));
		option.setOrdering(attrInt(node,"ordering"));
		DB.save(session, option);
	}
	
	private static Element addExpressionNode(Element parent, Expression expression) {
		Element expressionNode = parent.addElement("expression")
			.addAttribute("id", expression.getId()+"")
			.addAttribute("name", expression.getName())
			.addAttribute("key", expression.getRandomKey()+"")
			.addAttribute("questionId", expression.getQuestionId()+"")
			.addAttribute("resultForUnanswered", expression.getResultForUnanswered()+"")
			.addAttribute("type", expression.getTypeDB()+"")
			.addAttribute("operator", expression.getOperatorDB()+"");
		addText(expressionNode,"value",expression.getValueDB());
		return expressionNode;
	}
	
	private static void updateExpressionFromNode(Session session, Expression expression, Element node,
			Long studyId, Map<Long,Long> remoteToLocalQuestionId, Map<Long,Long> remoteToLocalOptionId,
			Map<Long,Long> remoteToLocalExpressionId)
	{
		expression.setStudyId(studyId);
		expression.setName(attrString(node,"name"));
		expression.setResultForUnanswered(attrBool(node,"resultForUnanswered"));
		expression.setTypeDB(attrString(node,"type"));
		expression.setOperatorDB(attrString(node,"operator"));
		
		// questionId
		Long remoteQuestionId = attrLong(node,"questionId");
		expression.setQuestionId(
				remoteQuestionId == null ? null : 
					remoteToLocalQuestionId.get(remoteQuestionId));
		
		// value (first set as normal, then convert IDs)
		expression.setValueDB(attrText(node,"value"));
		if(expression.getType().equals(Expression.Type.Selection) || 
				expression.getType().equals(Expression.Type.Compound))
		{
			List<Long> localValueIds = Lists.newArrayList();
			for(Long remoteValueId : (List<Long>) expression.getValue()) {
				Map<Long,Long> remoteToLocalValueId = 
					expression.getType().equals(Expression.Type.Compound) ?
							remoteToLocalExpressionId : remoteToLocalOptionId;
				Long localValueId = remoteToLocalValueId.get(remoteValueId);
				if(localValueId != null) {
					localValueIds.add(localValueId);
				}
			}
			expression.setValue(localValueIds);
		} else if(expression.getType().equals(Expression.Type.Comparison)) {
			Pair<Integer,Long> remoteNumberExpr = 
				(Pair<Integer,Long>) expression.getValue();
			expression.setValue(new Pair<Integer,Long>(
					remoteNumberExpr.getFirst(),
					remoteToLocalExpressionId.get(remoteNumberExpr.getSecond())));
		} else if(expression.getType().equals(Expression.Type.Counting)) {
			Triple<Integer,List<Long>,List<Long>> remoteNumberExprsQuests =
				(Triple<Integer,List<Long>,List<Long>>) expression.getValue();
			List<Long> localExprs = Lists.newArrayList();
			for(Long remoteExpr : remoteNumberExprsQuests.getSecond()) {
				Long localExpr = remoteToLocalExpressionId.get(remoteExpr);
				if(localExpr != null) {
					localExprs.add(localExpr);
				}
			}
			List<Long> localQuests = Lists.newArrayList();
			for(Long remoteQuest : remoteNumberExprsQuests.getThird()) {
				Long localQuest = remoteToLocalQuestionId.get(remoteQuest);
				if(localQuest != null) {
					localQuests.add(localQuest);
				}
			}
			expression.setValue(new Triple<Integer,List<Long>,List<Long>>(
					remoteNumberExprsQuests.getFirst(),
					localExprs, localQuests));
		}
		DB.save(session, expression);
	}
	
	private static Element addInterviewNode(Element parent, 
			Interview interview, List<Alter> alters, List<Answer> answers)
	{
		Element interviewNode = parent.addElement("interview")
			.addAttribute("id", interview.getId()+"")
			.addAttribute("key", interview.getRandomKey()+"");
		Element altersNode = interviewNode.addElement("alters");
		for(Alter alter : alters) {
			addAlterNode(altersNode,alter);
		}
		Element answersNode = interviewNode.addElement("answers");
		for(Answer answer : answers) {
			addAnswerNode(answersNode,answer);
		}
		return interviewNode;
	}
	
	private static void updateInterviewFromNode(Session session, Interview interview, Element node, Long studyId) 
	{
		interview.setStudyId(studyId);
		DB.save(session, interview);
	}
	
	private static Element addAlterNode(Element parent, Alter alter) {
		return parent.addElement("alter")
			.addAttribute("id", alter.getId()+"")
			.addAttribute("name", alter.getName())
			.addAttribute("key", alter.getRandomKey()+"");
	}
	
	private static void updateAlterFromNode(Session session, Alter alter, Element node, Long interviewId) {
		alter.setInterviewId(interviewId);
		alter.setName(attrString(node,"name"));
		DB.save(session, alter);
	}
	
	private static Element addAnswerNode(Element parent, Answer answer) {
		Element answerNode = parent.addElement("answer")
			.addAttribute("id", answer.getId()+"")
			.addAttribute("key", answer.getRandomKey()+"")
			.addAttribute("questionId", answer.getQuestionId()+"")
			.addAttribute("questionType", answer.getQuestionTypeDB())
			.addAttribute("skipReason", answer.getSkipReasonDB())
			.addAttribute("answerType", answer.getAnswerTypeDB())
			.addAttribute("alterId1", answer.getAlterId1()+"")
			.addAttribute("alterId2", answer.getAlterId2()+"");
		addText(answerNode,"value",answer.getValue());
		return answerNode;
	}
	
	private static void updateAnswerFromNode(Session session, Answer answer, Element node, 
			Long studyId, Long interviewId, Map<Long,Long> remoteToLocalQuestionId, 
			Map<Long,Long> remoteToLocalAlterId, Map<Long,Long> remoteToLocalOptionId)
	{
		answer.setStudyId(studyId);
		answer.setInterviewId(interviewId);
		answer.setQuestionTypeDB(attrString(node,"questionType"));
		answer.setSkipReasonDB(attrString(node,"skipReason"));
		answer.setAnswerTypeDB(attrString(node,"answerType"));
		
		// questionId
		Long remoteQuestionId = attrLong(node,"questionId");
		answer.setQuestionId(
				remoteQuestionId == null ? null : 
					remoteToLocalQuestionId.get(remoteQuestionId));
		
		// alterId1
		Long remoteAlterId1 = attrLong(node,"alterId1");
		answer.setAlterId1(
				remoteAlterId1 == null ? null : 
					remoteToLocalAlterId.get(remoteAlterId1));
		
		// alterId2
		Long remoteAlterId2 = attrLong(node,"alterId2");
		answer.setAlterId2(
				remoteAlterId2 == null ? null : 
					remoteToLocalAlterId.get(remoteAlterId2));
		
		// value (requires translation - see Archiving.updateExpressionFromNode and MultipleSelectionAnswerFormFieldPanel)
		String answerString = attrText(node,"value");
		Answer.AnswerType answerType = answer.getAnswerType();
		if(answerType.equals(Answer.AnswerType.SELECTION) ||
				answerType.equals(Answer.AnswerType.MULTIPLE_SELECTION))
		{
			String optionIds = "";
			try {
				for(String optionRemoteIdString : answerString.split(",")) {
					Long optionRemoteId = Long.parseLong(optionRemoteIdString);
					Long optionLocalId = remoteToLocalOptionId.get(optionRemoteId);
					if(optionLocalId != null) {
						optionIds += (optionIds.isEmpty() ? "" : ",")+optionLocalId;
					}
				}
			} catch(Exception ex) {
				// Most likely failed to parse answer. Fall back to no existing answer.
			}
			answer.setValue(optionIds);
		} else {
			answer.setValue(answerString);
		}
		DB.save(session, answer);
	}
	
	private static String formatXMLDocument(Document document) throws IOException {
		StringWriter stringWriter = new StringWriter();
		OutputFormat format = OutputFormat.createPrettyPrint();
		format.setNewlines(true);
		format.setTrimText(false);
		format.setLineSeparator("\r\n");
		new XMLWriter(stringWriter,format).write(document);
		return stringWriter.toString();
	}
	
	private static <E extends Entity> Map<Long,E> indexById(List<E> entities) {
		Map<Long,E> idToEntity = Maps.newTreeMap();
		for(E entity : entities) {
			idToEntity.put(entity.getId(), entity);
		}
		return idToEntity;
	}

	private static String attrText(Element element, String name) {
		Element textElement = element.element(name);
		if(textElement == null) {
			return null;
		}
		return textElement.getText();
	}
	
	private static String attrString(Element element, String name) {
		if(element == null || name == null) {
			throw new RuntimeException(
					"Unable to determine "+name+" attribute for "+
					(element == null ? "null " : "")+"element.");
		}
		Attribute attribute = element.attribute(name);
		if(attribute == null) {
			throw new RuntimeException("Element does not contain the requested attribute: "+name);
		}
		String attr = attribute.getValue();
		return
			attr == null || attr.isEmpty() || attr.equals("null") ?
					null : attr;
	}

	private static Long attrLong(Element element, String name) {
		String str = attrString(element,name);
		return str == null ? null : Long.parseLong(str);
	}
	
	private static Long attrId(Element element) {
		return attrLong(element,"id");
	}
	
	private static Integer attrInt(Element element, String name) {
		String str = attrString(element,name);
		return str == null ? null : Integer.parseInt(str);
	}
	
	private static Boolean attrBool(Element element, String name) {
		String str = attrString(element,name);
		if(str == null) {
			return null;
		}
		if(str.equalsIgnoreCase("true")) {
			return true;
		}
		if(str.equalsIgnoreCase("false")) {
			return false;
		}
		return null;
	}

	private static Function<Object,Question> fnCreateQuestion() {
		return new Function<Object,Question>() {
			public Question apply(Object arg0) {
				return new Question();
			}
		};
	}
	private static Function<Object,QuestionOption> fnCreateOption() {
		return new Function<Object,QuestionOption>() {
			public QuestionOption apply(Object arg0) {
				return new QuestionOption();
			}
		};
	}
	private static Function<Object,Expression> fnCreateExpression() {
		return new Function<Object,Expression>() {
			public Expression apply(Object arg0) {
				return new Expression();
			}
		};
	}
	private static Function<Object,Interview> fnCreateInterview() {
		return new Function<Object,Interview>() {
			public Interview apply(Object arg0) {
				return new Interview();
			}
		};
	}
	private static Function<Object,Alter> fnCreateAlter() {
		return new Function<Object,Alter>() {
			public Alter apply(Object arg0) {
				return new Alter();
			}
		};
	}
	private static Function<Object,Answer> fnCreateAnswer() {
		return new Function<Object,Answer>() {
			public Answer apply(Object arg0) {
				return new Answer();
			}
		};
	}

	private static Function<Question,Object> fnDeleteQuestion(final Session session) {
		return new Function<Question,Object>() {
			public Object apply(Question question) {
				Questions.delete(session, question);
				return null;
			}
		};
	}
	private static Function<QuestionOption,Object> fnDeleteOption(final Session session) {
		return new Function<QuestionOption,Object>() {
			public Object apply(QuestionOption option) {
				Options.delete(session, option);
				return null;
			}
		};
	}
	private static Function<Expression,Object> fnDeleteExpression(final Session session) {
		return new Function<Expression,Object>() {
			public Object apply(Expression expression) {
				Expressions.delete(session, expression);
				return null;
			}
		};
	}
	private static Function<Alter,Object> fnDeleteAlter(final Session session) {
		return new Function<Alter,Object>() {
			public Object apply(Alter alter) {
				Alters.delete(session, alter);
				return null;
			}
		};
	}
}
