assert new File(basedir,'prompt-contracts.lock.json').text.contains('events.announcement-extraction')
assert new File(basedir,'target/reports/prompt-contracts/validate/validation.json').text.contains('\"valid\" : true')
