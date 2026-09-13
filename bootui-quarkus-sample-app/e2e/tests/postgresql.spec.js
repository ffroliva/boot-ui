import {expect, test} from '@playwright/test'
import {registerPostgresqlTests} from '../../../bootui-spring-sample-app/e2e/scenarios/postgresql.js'

registerPostgresqlTests(test, expect)
