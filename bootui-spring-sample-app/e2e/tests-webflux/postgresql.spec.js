import {expect, test} from '@playwright/test'
import {registerPostgresqlTests} from '../scenarios/postgresql.js'

registerPostgresqlTests(test, expect)
