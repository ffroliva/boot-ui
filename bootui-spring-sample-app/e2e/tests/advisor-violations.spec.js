// @ts-check
import {expect, test} from '@playwright/test'
import {registerAdvisorViolationTests} from '../scenarios/advisor-violations.js'

registerAdvisorViolationTests(test, expect)
