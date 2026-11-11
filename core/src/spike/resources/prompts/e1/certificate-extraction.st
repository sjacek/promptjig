Extract data from a Polish disability certificate OCR text.
Return only one valid JSON object. Do not include markdown.

Required JSON keys:
patientFirstNames, patientSurname, nationalId, country, certificateType, degree,
certificateNumber, issuingBodyName, issuedAt, validFrom, validTo, permanent,
symbolCodes, indicationCodes, notes.

Rules:
- Use null when the value is not clearly present.
- Dates must use ISO format yyyy-mm-dd.
- country should be "PL" unless another country is clearly present.
- certificateType must be one of: PZON_CHILD, PZON_ADULT_DEGREE, ZUS_WORK_INCAPACITY.
- degree must be one of: MILD, MODERATE, SEVERE.
- symbolCodes must contain values like 01-U, 02-P, 03-L, 04-O, 05-R, 06-E, 07-S, 08-T, 09-M, 10-N, 11-I, 12-C.
- indicationCodes should contain only explicit indication codes when present.
- Do not infer identity data from outside the OCR text.

OCR text:
{ocrText}
