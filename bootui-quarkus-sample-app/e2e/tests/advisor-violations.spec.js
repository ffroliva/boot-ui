// @ts-check
import {expect, test} from '@playwright/test'
import {registerAdvisorViolationTests} from '../../../bootui-spring-sample-app/e2e/scenarios/advisor-violations.js'

registerAdvisorViolationTests(test, expect)
