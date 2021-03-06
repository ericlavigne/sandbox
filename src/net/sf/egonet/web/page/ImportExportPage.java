package net.sf.egonet.web.page;

import java.util.ArrayList;

import net.sf.egonet.model.Study;
import net.sf.egonet.persistence.Archiving;
import net.sf.egonet.persistence.Studies;

import org.apache.wicket.markup.html.form.Button;
import org.apache.wicket.markup.html.form.DropDownChoice;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.upload.FileUploadField;
import org.apache.wicket.model.Model;
import org.apache.wicket.util.lang.Bytes;

public class ImportExportPage extends EgonetPage {
	
	private ArrayList<Study> studies;
	
	public ImportExportPage() {

		studies = new ArrayList<Study>(Studies.getStudies());
		
		add(buildStudyImportForm());
		add(buildStudyModifyForm());
		add(buildRespondentDataImportForm());
		add(buildExportForm());
	}
	
	private DropDownChoice createStudyDropdown(String wicketId) {
		return new DropDownChoice(wicketId,
				studies.size() == 1 ? new Model(studies.get(0)) : new Model(),
				studies);
	}
	
	private Study getStudy(DropDownChoice studyDropdown) {
		return (Study) studyDropdown.getModelObject();
	}

	private Form buildStudyImportForm() {

		final FileUploadField studyImportField = new FileUploadField("studyImportField");
		Form studyImportForm = new Form("studyImportForm") {
			public void onSubmit() {
				try {
					String uploadText = uploadText(studyImportField);
					if(uploadText != null) {
						Archiving.loadStudyXML(null, uploadText);
					}
					setResponsePage(new ImportExportPage());
				} catch(Exception ex) {
					throw new RuntimeException("Exception while trying to import study.",ex);
				}
			}
		};
		studyImportForm.setMultiPart(true);
		studyImportForm.add(studyImportField);
		studyImportForm.setMaxSize(Bytes.megabytes(100));
		
		return studyImportForm;
	}
	
	private Form buildStudyModifyForm() {

		final DropDownChoice studyToModify = createStudyDropdown("studyToModify");
		
		final FileUploadField studyImportField = new FileUploadField("studyModifyField");
		Form studyImportForm = new Form("studyModifyForm") {
			public void onSubmit() {
				try {
					String uploadText = uploadText(studyImportField);
					Study study = getStudy(studyToModify);
					if(uploadText != null && study != null) {
						Archiving.loadStudyXML(study, uploadText);
						setResponsePage(new ImportExportPage());
					}
					if(uploadText == null) {
						throw new RuntimeException("Need to specify a study settings file.");
					}
					if(study == null) {
						throw new RuntimeException("Need to specify a study.");
					}
				} catch(Exception ex) {
					throw new RuntimeException("Exception while trying to import study.",ex);
				}
			}
		};
		studyImportForm.setMultiPart(true);
		studyImportForm.add(studyToModify);
		studyImportForm.add(studyImportField);
		studyImportForm.setMaxSize(Bytes.megabytes(100));
		
		return studyImportForm;
	}
	
	private Form buildRespondentDataImportForm() {

		final DropDownChoice studyToPopulate = createStudyDropdown("studyToPopulate");
		final FileUploadField respondentDataImportField = new FileUploadField("respondentDataImportField");
		
		Form respondentDataImportForm = new Form("respondentDataImportForm") {
			public void onSubmit() {
				try {
					String uploadText = uploadText(respondentDataImportField);
					Study study = getStudy(studyToPopulate);
					if(uploadText != null && study != null) {
						Archiving.loadRespondentXML(study, uploadText);
					}
				} catch(Exception ex) {
					throw new RuntimeException("Exception while trying to import respondent data.",ex);
				}
			}
		};
		respondentDataImportForm.setMultiPart(true);
		respondentDataImportForm.add(studyToPopulate);
		respondentDataImportForm.add(respondentDataImportField);
		respondentDataImportForm.setMaxSize(Bytes.megabytes(100));
		return respondentDataImportForm;
	}
	
	private DropDownChoice exportDropdown;
	
	private Form buildExportForm() {
		Form exportForm = new Form("exportForm");
		exportDropdown = createStudyDropdown("studyToExport");
		exportForm.add(exportDropdown);
		exportForm.add(buildStudyExportButton());
		exportForm.add(buildRespondentDataExportButton());
		return exportForm;
	}
	
	private Button buildStudyExportButton() {
		return new Button("studyExport") {
			public void onSubmit() {
				Study study = getStudy(exportDropdown);
				downloadText(
						study.getName()+".study",
						"application/octet-stream",
						Archiving.getStudyXML(study));
			}
		};
	}
	
	private Button buildRespondentDataExportButton() {
		return new Button("respondentDataExport") {
			public void onSubmit() {
				Study study = getStudy(exportDropdown);
				downloadText(
						study.getName()+".responses",
						"application/octet-stream",
						Archiving.getRespondentDataXML(study));
			}
		};
	}
}
