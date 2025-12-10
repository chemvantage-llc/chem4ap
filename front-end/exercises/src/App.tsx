import React from 'react';
import SplashPage from './components/SplashPage';
import QuestionPrompt from './components/QuestionPrompt';
import AnswerForm from './components/AnswerForm';
import { useState, useEffect, useRef, useCallback } from 'react';
import { Question } from './types';

const App: React.FC = () => {
  const [page, setPage] = useState(0);
  const [question, setQuestion] = useState<Question>({
    id: 0,
    type: '',
    prompt: '',
    choices: [],
    scrambled: false,
    parameter: 0,
    correctAnswer: '',
    studentAnswer: '',
    units: ''
  });
  const [message, setMessage] = useState<string>('');
  const tokenRef = useRef<string | null>(null);
    
  useEffect(() => {
    const urlParams = new URLSearchParams(window.location.search);
    const token = urlParams.get('t');
    if (token) {
      tokenRef.current = token;
    } else {
      setMessage('No token found. Please launch this app from your LMS');
    }
  }, []);

  useEffect(() => {
    if (message !== "") {
      setPage(0);
      return;
    }
    const timer = setTimeout(() => {
      setPage(1);
    }, 2000);
    return () => clearTimeout(timer);
  }, [message]);

  const getQuestion = useCallback(async () => {
    if (page !== 1) return;
    try {
      if (!tokenRef.current) {
        throw new Error('Auth token missing. Please launch this app from your LMS');
      }
      const response = await fetch('/exercises', {
        method: 'GET',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': 'Bearer ' + tokenRef.current
        }
      });
      if (!response.ok) {
        throw new Error('The one-time token was invalid. Please launch this app from your LMS');
      }
      const data = await response.json();
      if (data.token !== null) tokenRef.current = data.token;
      if (data.question !== null) setQuestion(data.question);
      console.log("Fetched a question");
    } catch (error) {
      console.error('Error fetching question:', error);
      if (error instanceof Error) {
        setMessage(error.message);
      } else {
        setMessage('An unknown error occurred');
      }
    }
  }, [page]);

  // Fetch the first question when the app loads
  useEffect(() => {
    getQuestion();
  }, [getQuestion]);

  return (
    <div className="container">
      {page === 0 && <SplashPage message={message} />}
      {page > 0 && <QuestionPrompt question={question} />}
      {page > 0 && question.prompt && <AnswerForm tokenRef={tokenRef} question={question} page={page} setPage={setPage} />}
    </div>
  );
};

export default App;
