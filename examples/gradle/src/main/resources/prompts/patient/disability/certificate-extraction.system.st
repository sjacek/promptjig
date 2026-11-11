Extract data from a Polish disability certificate OCR text.
Return only one valid JSON object. Do not include markdown.

Required JSON keys:
{contract.output.fieldText}.

Rules:
- Use null when the value is not clearly present. Use empty lists for absent arrays.
- Dates must use ISO format yyyy-mm-dd.
- country should be "PL" unless another country is clearly present.
- certificateType must be one of: {contract.output.enumText.certificateType}.
- degree must be one of: {contract.output.enumText.degree}.
- symbolCodes must contain values from: {contract.output.enumText.symbolCodes}.
- indicationCodes should contain only explicit codes from: {contract.output.enumText.indicationCodes}.
- Do not infer identity data from outside the OCR text.
